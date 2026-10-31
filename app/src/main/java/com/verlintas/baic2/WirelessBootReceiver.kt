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

package com.verlintas.baic2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.device.api.WirelessAdbBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Best-effort re-arm after reboot or an app update. The wireless transport
 * holds the pairing key in app-private storage; with WRITE_SECURE_SETTINGS it
 * can turn the system toggle back on and reconnect without another pairing.
 * The display keepalive is started even when wireless auto-enable is off, so
 * an existing session can be re-checked once a transport becomes available.
 */
@AndroidEntryPoint
class WirelessBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var wireless: WirelessAdbBridge

    @Inject
    lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!boot && !updated) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (boot && wireless.autoEnable) {
                    runCatching { wireless.connect() }
                }
                if (settings.keepDisplayAlive.first()) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, VirtualDisplayKeepAliveService::class.java),
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
