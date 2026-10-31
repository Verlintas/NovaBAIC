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

import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.verlintas.baic2.device.api.VirtualDisplayHandoff

/**
 * Standalone entry point for the resident display helper.
 *
 * The app starts this class with `app_process` through Shizuku or root. It
 * creates [ShellDisplayService] and hands its Binder to the app's provider,
 * then stays in the main loop so the VirtualDisplay lives between research
 * calls.
 *
 * The handoff goes through ActivityManager's external-provider path instead
 * of ContentResolver.call: the system context reports package `android`
 * (uid 1000), while this process is shell (uid 2000), and ContentResolver
 * rejects that mismatch before the provider is reached.
 *
 * No Hilt, no app context: this process only has the shell/root identity.
 */
object DisplayHelperMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val authority = arg(args, "--authority")
        val packageName = arg(args, "--package")
        val appUid = arg(args, "--uid")?.toIntOrNull()
        val userId = arg(args, "--user")?.toIntOrNull() ?: 0
        val nonce = arg(args, "--nonce")
        if (authority.isNullOrBlank() || packageName.isNullOrBlank() ||
            appUid == null || nonce.isNullOrBlank()
        ) {
            println("HELPER_ERROR=missing authority, package, uid or nonce")
            System.out.flush()
            return
        }

        Looper.prepareMainLooper()
        val context = ShellContext.create()
        val service = ShellDisplayService(context)

        Thread({
            val accepted = runCatching {
                attach(authority, packageName, appUid, userId, nonce, service)
            }.onFailure {
                it.printStackTrace()
            }.getOrDefault(false)

            if (accepted) {
                println("HELPER_READY")
                System.out.flush()
            } else {
                println("HELPER_ERROR=handoff rejected")
                System.out.flush()
                kotlin.system.exitProcess(2)
            }
        }, "baic2-display-handoff").start()

        Looper.loop()
    }

    /**
     * Fetches the app's provider by authority and calls it directly. The
     * provider permission still sees shell/root as the caller, and the
     * package/uid check rejects a same-named authority owned by another app.
     */
    private fun attach(
        authority: String,
        packageName: String,
        appUid: Int,
        userId: Int,
        nonce: String,
        service: ShellDisplayService,
    ): Boolean {
        val amClass = Class.forName("android.app.ActivityManager")
        val am = amClass.getMethod("getService").invoke(null) ?: return false
        val token = Binder()
        val holder = am.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java,
            Int::class.javaPrimitiveType,
            IBinder::class.java,
            String::class.java,
        ).invoke(am, authority, userId, token, "BAIC2") ?: return false

        try {
            val holderClass = holder.javaClass
            val info = holderClass.getField("info").get(holder) as? ProviderInfo
                ?: return false
            if (info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                return false
            }
            val provider = holderClass.getField("provider").get(holder) ?: return false
            val extras = Bundle().apply {
                putBinder(VirtualDisplayHandoff.KEY_BINDER, service.asBinder())
                putInt(VirtualDisplayHandoff.KEY_PID, Process.myPid())
                putString(VirtualDisplayHandoff.KEY_NONCE, nonce)
            }
            val result = invokeProviderCall(
                provider = provider,
                authority = authority,
                extras = extras,
            ) ?: return false
            if (result?.getBoolean(VirtualDisplayHandoff.KEY_ACCEPTED) != true) return false
            result.getBinder(VirtualDisplayHandoff.KEY_APP_TOKEN)?.let { appToken ->
                runCatching {
                    appToken.linkToDeath({ kotlin.system.exitProcess(0) }, 0)
                }
            }
            return true
        } finally {
            runCatching {
                am.javaClass.getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java,
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(am, authority, token, userId)
            }
        }
    }

    /**
     * Android 11+ exposes `call(AttributionSource, authority, method, arg,
     * extras)` on IContentProvider; older releases use the four-argument
     * form. Both carry the binder in `extras`.
     */
    private fun invokeProviderCall(
        provider: Any,
        authority: String,
        extras: Bundle,
    ): Bundle? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val five = provider.javaClass.methods.firstOrNull { method ->
                method.name == "call" &&
                    method.parameterTypes.size == 5 &&
                    method.parameterTypes[0] == AttributionSource::class.java
            }
            if (five != null) {
                val caller = AttributionSource.Builder(Process.myUid())
                    .setPackageName(ShellContext.SHELL_PACKAGE)
                    .build()
                return five.invoke(
                    provider,
                    caller,
                    authority,
                    VirtualDisplayHandoff.METHOD_ATTACH,
                    null,
                    extras,
                ) as? Bundle
            }
        }
        val four = provider.javaClass.methods.firstOrNull { method ->
            method.name == "call" &&
                method.parameterTypes.size == 4 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[3] == Bundle::class.java
        } ?: return null
        return four.invoke(
            provider,
            authority,
            VirtualDisplayHandoff.METHOD_ATTACH,
            null,
            extras,
        ) as? Bundle
    }

    private fun arg(args: Array<String>, key: String): String? {
        val index = args.indexOf(key)
        return if (index >= 0) args.getOrNull(index + 1) else null
    }
}
