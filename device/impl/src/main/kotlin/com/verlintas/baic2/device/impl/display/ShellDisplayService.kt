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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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

    /**
     * A DisplayManager built on the shell context. The system-managed
     * instance reports the system package and is rejected for uid 2000, so
     * the helper constructs its own manager through the hidden constructor.
     */
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager? = runCatching {
        val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        constructor.newInstance(context) as DisplayManager
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
        // `am` already runs inside the shell helper, so it is both simpler and
        // more OEM-tolerant than reflecting into ActivityTaskManager. The
        // helper still avoids an extra ADB round trip.
        return amStart(displayId, target.flattenToString())
    }

    private fun amStart(displayId: Int, component: String): Boolean = runCatching {
        val argv = listOf(
            "am", "start", "--display", displayId.toString(),
            "-f", "0x10008000", "-n", component,
        )
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroy()
        finished && process.exitValue() == 0
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
        var flags = DISPLAY_PUBLIC or DISPLAY_OWN_CONTENT or DISPLAY_TOUCH or
            DISPLAY_ROTATE or DISPLAY_DESTROY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or DISPLAY_TRUSTED or DISPLAY_OWN_GROUP or
                DISPLAY_UNLOCKED or DISPLAY_NO_TOUCH_FEEDBACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Take input focus on the virtual display without stealing the
            // user's main-screen focus.
            flags = flags or DISPLAY_OWN_FOCUS or DISPLAY_KEEP_MAIN_FOCUS
        }
        return flags
    }

    private fun setImePolicy(displayId: Int) {
        val viaWindowService = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "window") as? IBinder
                ?: return@runCatching false
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val manager = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                ?: return@runCatching false
            val call = manager.javaClass.methods.firstOrNull {
                it.name == "setDisplayImePolicy" && it.parameterTypes.size == 2
            } ?: return@runCatching false
            call.invoke(manager, displayId, IME_POLICY_LOCAL)
            true
        }.getOrDefault(false)
        if (viaWindowService) return

        // Some OEM builds only expose the WindowManagerGlobal accessor.
        runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val accessor = global.getDeclaredMethod("getWindowManagerService")
                .apply { isAccessible = true }
            val manager = accessor.invoke(null) ?: return@runCatching
            manager.javaClass.methods.firstOrNull {
                it.name == "setDisplayImePolicy" && it.parameterTypes.size == 2
            }?.invoke(manager, displayId, IME_POLICY_LOCAL)
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

    private companion object {
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (some are hidden constants).
        const val DISPLAY_PUBLIC = 1 shl 0
        const val DISPLAY_OWN_CONTENT = 1 shl 3
        const val DISPLAY_TOUCH = 1 shl 6
        const val DISPLAY_ROTATE = 1 shl 7
        const val DISPLAY_DESTROY = 1 shl 8
        const val DISPLAY_TRUSTED = 1 shl 10
        const val DISPLAY_OWN_GROUP = 1 shl 11
        const val DISPLAY_UNLOCKED = 1 shl 12
        const val DISPLAY_NO_TOUCH_FEEDBACK = 1 shl 13
        const val DISPLAY_OWN_FOCUS = 1 shl 14
        const val DISPLAY_KEEP_MAIN_FOCUS = 1 shl 16

        // WindowManager.DISPLAY_IME_POLICY_LOCAL
        const val IME_POLICY_LOCAL = 0
    }
}
