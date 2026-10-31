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

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.verlintas.baic2.device.api.WirelessAdbBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debug-only: pair the built-in wireless ADB transport with a supplied code. */
@AndroidEntryPoint
class DebugWirelessService : Service() {

    @Inject
    lateinit var wireless: WirelessAdbBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getStringExtra("code")?.trim().orEmpty()
        if (code.length != 6) {
            Log.e(TAG, "missing pairing code")
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            delay(500L)
            val paired = wireless.pair(code)
            Log.i(TAG, "pair=${paired.isSuccess} ${paired.exceptionOrNull()?.message.orEmpty()}")
            val connected = wireless.connect()
            Log.i(TAG, "connect=${connected.isSuccess} state=${wireless.state.value} " +
                connected.exceptionOrNull()?.message.orEmpty())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val TAG = "BAIC2WA"
    }
}
