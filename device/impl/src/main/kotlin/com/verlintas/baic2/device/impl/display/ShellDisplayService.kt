/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.device.impl.display

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.verlintas.baic2.device.api.IVirtualDisplayService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The privileged half of the resident helper.
 *
 * It runs inside `app_process` with shell (or root) identity, holds the
 * VirtualDisplay objects so they survive between research calls, and is
 * handed back to the app through [com.verlintas.baic2.device.api.VirtualDisplayHandoff].
 */
class ShellDisplayService(private val context: Context) : IVirtualDisplayService.Stub() {

    private val main = Handler(Looper.getMainLooper())
    private val displays = HashMap<Int, VirtualDisplay>()

    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager? = runCatching {
        // getSystemService() returns a manager bound to the system context,
        // whose package name does not match the shell uid. Construct it with
        // our shell context so createVirtualDisplay() sees com.android.shell.
        DisplayManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
    }.getOrNull()

    override fun createDisplay(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
        name: String?,
    ): Int {
        if (surface == null || width <= 0 || height <= 0) return -1
        val manager = displayManager ?: return -1
        return onMain {
            runCatching {
                val display = manager.createVirtualDisplay(
                    name?.takeIf { it.isNotBlank() } ?: "baic2-agent",
                    width,
                    height,
                    dpi,
                    surface,
                    displayFlags(),
                ) ?: return@runCatching -1
                val id = display.display?.displayId ?: return@runCatching -1
                synchronized(displays) { displays[id] = display }
                setImePolicy(id)
                id
            }.getOrDefault(-1)
        } ?: -1
    }

    override fun releaseDisplay(displayId: Int) {
        val display = synchronized(displays) { displays.remove(displayId) } ?: return
        onMain {
            runCatching { display.release() }
            Unit
        }
    }

    override fun startApp(displayId: Int, component: String?): Boolean {
        if (component.isNullOrBlank() || !ownsDisplay(displayId)) return false
        val target = ComponentName.unflattenFromString(component) ?: return false
        val intent = Intent()
            .setComponent(target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(displayId)
            .toBundle()
        // Context.startActivity() uses the system context's package "android"
        // and is rejected for uid 2000. Talk to ActivityTaskManager directly
        // with the shell package, which is the same identity adb shell uses.
        return runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atm = atmClass.getMethod("getService").invoke(null) ?: return false
            val methods = atm.javaClass.methods
            val userId = android.os.Process.myUid() / 100_000
            val asUser = methods.firstOrNull { method ->
                method.name == "startActivityAsUser" &&
                    method.parameterTypes.size == 12 &&
                    method.parameterTypes[3] == Intent::class.java
            }
            if (asUser != null) {
                val result = asUser.invoke(
                    atm,
                    null,
                    ShellContext.SHELL_PACKAGE,
                    null,
                    intent,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    options,
                    userId,
                ) as? Int ?: -1
                return@runCatching result == 0
            }
            val direct = methods.firstOrNull { method ->
                method.name == "startActivity" &&
                    method.parameterTypes.size == 11 &&
                    method.parameterTypes[3] == Intent::class.java
            } ?: return@runCatching false
            val result = direct.invoke(
                atm,
                null,
                ShellContext.SHELL_PACKAGE,
                null,
                intent,
                null,
                null,
                null,
                0,
                0,
                null,
                options,
            ) as? Int ?: -1
            if (result == 0) return@runCatching true
            // ActivityTaskManager can reject a display launch (START_CANCELED);
            // the shell command is the reliable fallback and stays inside the
            // helper, so the app still avoids an extra ADB round trip.
            amStart(displayId, component)
        }.onFailure {
            android.util.Log.e("BAIC2HELPER", "startApp failed", it)
        }.getOrDefault(false)
    }

    private fun amStart(displayId: Int, component: String): Boolean = runCatching {
        val process = ProcessBuilder(
            "am",
            "start",
            "--display",
            displayId.toString(),
            "-f",
            "0x10008000",
            "-n",
            component,
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)

    override fun tap(displayId: Int, x: Int, y: Int): Boolean =
        input(displayId, "tap", x.toString(), y.toString())

    override fun swipe(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Boolean = input(
        displayId,
        "swipe",
        x1.toString(),
        y1.toString(),
        x2.toString(),
        y2.toString(),
        durationMs.coerceIn(50, 2_000).toString(),
    )

    override fun keyEvent(displayId: Int, keyCode: Int): Boolean =
        input(displayId, "keyevent", keyCode.toString())

    override fun typeText(displayId: Int, text: String?): Boolean {
        if (text.isNullOrEmpty() || !ownsDisplay(displayId)) return false
        if (text.all { it.code in 0x20..0x7e }) {
            return input(displayId, "text", text.replace(" ", "%s"))
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val old = runCatching { manager.primaryClip }.getOrNull()
        val set = onMain {
            runCatching {
                manager.setPrimaryClip(ClipData.newPlainText("BAIC2 helper", text))
                true
            }.getOrDefault(false)
        } ?: false
        if (!set) return false
        val pasted = input(displayId, "keyevent", "279")
        Thread.sleep(300L)
        onMain {
            runCatching { old?.let { manager.setPrimaryClip(it) } }
            Unit
        }
        return pasted
    }

    override fun ping(): Boolean = true

    private fun ownsDisplay(displayId: Int): Boolean =
        synchronized(displays) { displayId in displays }

    private fun input(displayId: Int, vararg args: String): Boolean {
        if (!ownsDisplay(displayId)) return false
        return runCatching {
            val command = arrayListOf("input", "-d", displayId.toString())
            command += args
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    private fun displayFlags(): Int {
        // PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT |
        // DESTROY_CONTENT_ON_REMOVAL
        var flags = 1 or 8 or 64 or 128 or 256
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED
            flags = flags or (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED: the display can take input
            // focus without stealing the user's main-screen focus.
            flags = flags or (1 shl 14) or (1 shl 16)
        }
        return flags
    }

    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, 0) // DISPLAY_IME_POLICY_LOCAL
        }
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        main.post {
            result = runCatching(block).getOrNull()
            latch.countDown()
        }
        if (!latch.await(8, TimeUnit.SECONDS)) return null
        return result
    }
}
