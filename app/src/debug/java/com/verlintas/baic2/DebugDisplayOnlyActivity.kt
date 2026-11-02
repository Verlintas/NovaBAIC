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
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.verlintas.baic2.device.api.VirtualDisplayBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Debug-only: create a virtual display and hold it for adb launch probes. */
@AndroidEntryPoint
class DebugDisplayOnlyActivity : ComponentActivity() {

    @Inject
    lateinit var displays: VirtualDisplayBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { text = "Creating virtual display…" }
        setContentView(view)
        lifecycleScope.launch {
            val result = displays.ensure()
            val id = result.getOrNull()?.displayId
            Log.i(TAG, "ensure=$result display=$id")
            view.text = if (id != null) {
                "Virtual display ready: $id\nLeave this screen open for adb probes."
            } else {
                "Virtual display failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    private companion object {
        const val TAG = "BAIC2DSP"
    }
}
