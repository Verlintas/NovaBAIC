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

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.data.repository.RunRepository
import com.verlintas.baic2.device.api.VirtualDisplayBridge
import com.verlintas.baic2.device.api.WirelessAdbBridge
import com.verlintas.baic2.mcp.McpManager
import com.verlintas.baic2.tools.automation.AutomationBootstrap
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class Baic2Application : Application() {

    @Inject
    lateinit var automationBootstrap: AutomationBootstrap

    @Inject
    lateinit var mcpManager: McpManager

    @Inject
    lateinit var runRepository: RunRepository

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var wirelessAdb: WirelessAdbBridge

    @Inject
    lateinit var virtualDisplay: VirtualDisplayBridge

    override fun onCreate() {
        super.onCreate()
        runCatching { automationBootstrap.start() }
        runCatching { wirelessAdb.start() }
        CoroutineScope(Dispatchers.IO).launch {
            // Prewarm the resident helper so the first research request does
            // not pay the app_process/VirtualDisplay cold start.
            runCatching { if (virtualDisplay.available()) virtualDisplay.ensure() }
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { mcpManager.refresh() }
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { runRepository.cancelStaleRuns() }
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (settings.keepDisplayAlive.first()) {
                    ContextCompat.startForegroundService(
                        this@Baic2Application,
                        Intent(
                            this@Baic2Application,
                            VirtualDisplayKeepAliveService::class.java,
                        ),
                    )
                }
            }
        }
    }
}
