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
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.verlintas.baic2.core.data.repository.AgentRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Debug-only: replace the first agent's API key without fighting the IME. */
@AndroidEntryPoint
class DebugAgentActivity : ComponentActivity() {

    @Inject
    lateinit var agents: AgentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val key = intent.getStringExtra("key")?.trim().orEmpty()
            if (key.length < 20) {
                Log.e(TAG, "key missing or too short")
                finish()
                return@launch
            }
            val agent = agents.observeAgents().first().firstOrNull()
            if (agent == null) {
                Log.e(TAG, "no agent configured")
                finish()
                return@launch
            }
            val result = runCatching { agents.update(agent, key) }
            Log.i(
                TAG,
                "updated=${result.isSuccess} id=${agent.id} model=${agent.model} " +
                    "keyLen=${key.length} tail=${key.takeLast(4)}",
            )
            finish()
        }
    }

    private companion object {
        const val TAG = "BAIC2AG"
    }
}
