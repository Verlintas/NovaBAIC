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
import androidx.annotation.RequiresApi
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
        val context = createShellContext()
        val service = ShellDisplayService(context)

        Thread({
            val accepted = runCatching {
                attach(context, authority, packageName, appUid, userId, nonce, service)
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
     * The helper is an `app_process` without an ApplicationThread, so
     * ContentResolver cannot resolve an app provider. Ask ActivityManager for
     * an external provider handle instead, verify that the authority belongs
     * to the expected package/uid, and call it directly.
     */
    private fun attach(
        context: android.content.Context,
        authority: String,
        packageName: String,
        appUid: Int,
        userId: Int,
        nonce: String,
        service: ShellDisplayService,
    ): Boolean {
        // The context is intentionally unused here: the external-provider path
        // is the only one that works for an app_process caller.
        val activityManager = activityManager()
        if (activityManager == null) return false
        val token = Binder()
        val holder = acquireProvider(activityManager, authority, userId, token)
        if (holder == null) return false
        return try {
            val info = providerInfo(holder)
            if (info == null || !belongsTo(info, packageName, appUid)) return false
            val provider = providerBinder(holder)
            if (provider == null) return false
            val reply = invokeProviderCall(
                provider = provider,
                authority = authority,
                extras = handoffExtras(service, nonce),
            ) ?: return false
            if (reply.getBoolean(VirtualDisplayHandoff.KEY_ACCEPTED) != true) return false
            watchApp(reply.getBinder(VirtualDisplayHandoff.KEY_APP_TOKEN))
            true
        } finally {
            releaseProvider(activityManager, authority, token, userId)
        }
    }

    private fun activityManager(): Any? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "activity") as? IBinder
            ?: return@runCatching null
        Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.getOrNull()

    private fun acquireProvider(
        activityManager: Any,
        authority: String,
        userId: Int,
        token: IBinder,
    ): Any? {
        val method = activityManager.javaClass.methods.firstOrNull {
            it.name == "getContentProviderExternal" && it.parameterTypes.size == 4
        } ?: return null
        return runCatching {
            method.invoke(activityManager, authority, userId, token, CALLER_TAG)
        }.getOrNull()
    }

    private fun belongsTo(info: ProviderInfo, packageName: String, appUid: Int): Boolean =
        packageName == info.packageName && appUid == info.applicationInfo?.uid

    private fun providerInfo(holder: Any): ProviderInfo? = runCatching {
        holder.javaClass.getField("info").get(holder) as? ProviderInfo
    }.getOrNull()

    private fun providerBinder(holder: Any): Any? = runCatching {
        holder.javaClass.getField("provider").get(holder)
    }.getOrNull()

    private fun releaseProvider(
        activityManager: Any,
        authority: String,
        token: IBinder,
        userId: Int,
    ) {
        val method = activityManager.javaClass.methods.firstOrNull {
            it.name == "removeContentProviderExternalAsUser" && it.parameterTypes.size == 3
        } ?: return
        runCatching { method.invoke(activityManager, authority, token, userId) }
    }

    private fun handoffExtras(service: ShellDisplayService, nonce: String): Bundle =
        Bundle().apply {
            putBinder(VirtualDisplayHandoff.KEY_BINDER, service.asBinder())
            putInt(VirtualDisplayHandoff.KEY_PID, Process.myPid())
            putString(VirtualDisplayHandoff.KEY_NONCE, nonce)
        }

    private fun watchApp(token: IBinder?) {
        token ?: return
        runCatching { token.linkToDeath({ kotlin.system.exitProcess(0) }, 0) }
    }

    private fun invokeProviderCall(
        provider: Any,
        authority: String,
        extras: Bundle,
    ): Bundle? {
        val legacy = provider.javaClass.methods.firstOrNull { method ->
            method.name == "call" &&
                method.parameterTypes.size == 4 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[3] == Bundle::class.java
        }
        if (legacy != null) {
            val reply = runCatching {
                legacy.invoke(provider, authority, VirtualDisplayHandoff.METHOD_ATTACH, null, extras)
            }.getOrNull() as? Bundle
            if (reply != null) return reply
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val modern = provider.javaClass.methods.firstOrNull { method ->
            method.name == "call" &&
                method.parameterTypes.size == 5 &&
                method.parameterTypes[0] == AttributionSource::class.java
        } ?: return null
        return runCatching {
            modern.invoke(
                provider,
                attributionSource(),
                authority,
                VirtualDisplayHandoff.METHOD_ATTACH,
                null,
                extras,
            )
        }.getOrNull() as? Bundle
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun attributionSource(): AttributionSource {
        val builder = AttributionSource.Builder(Process.myUid())
        builder.setPackageName(ShellIdentity.PACKAGE)
        return builder.build()
    }

    private fun arg(args: Array<String>, key: String): String? {
        val keyIndex = args.indexOf(key)
        return if (keyIndex < 0) null else args.getOrNull(keyIndex + 1)
    }

    private const val CALLER_TAG = "BAIC2"
}
