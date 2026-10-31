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

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.verlintas.baic2.device.api.VirtualDisplayBridge
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debug-only smoke test for the resident virtual display. */
@AndroidEntryPoint
class DebugDisplayActivity : ComponentActivity() {

    @Inject
    lateinit var displays: VirtualDisplayBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val started = displays.ensure()
            Log.i(TAG, "ensure=$started")
            val session = started.getOrNull()
            if (session == null) {
                finish()
                return@launch
            }
            val again = displays.ensure().getOrNull()
            Log.i(TAG, "reuse=${again?.displayId} same=${again?.displayId == session.displayId}")
            val launched = displays.launch("com.android.settings/.Settings")
            Log.i(TAG, "launch=$launched")
            SystemClock.sleep(1_500)
            repeat(3) { attempt ->
                val frame = displays.capture()
                val bytes = frame.getOrNull()
                Log.i(TAG, "capture[$attempt]=${bytes?.size} ${frame.exceptionOrNull()?.message}")
                if (bytes != null) {
                    File(getExternalFilesDir(null), "debug-vd.png").writeBytes(bytes)
                    finish()
                    return@launch
                }
                delay(500)
            }
            finish()
        }
    }

    private companion object {
        const val TAG = "BAIC2VD"
    }
}
