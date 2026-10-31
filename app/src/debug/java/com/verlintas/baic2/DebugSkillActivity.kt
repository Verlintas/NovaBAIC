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
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolCallStatus
import com.verlintas.baic2.tools.skills.SkillRepository
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Debug-only smoke test for auto-skill save, dedupe, list and delete. */
@AndroidEntryPoint
class DebugSkillActivity : ComponentActivity() {

    @Inject
    lateinit var skills: SkillRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val calls = listOf(
                ToolCall(
                    id = "debug-open",
                    name = "open_settings",
                    argumentsJson = """{"page":"settings"}""",
                    status = ToolCallStatus.DONE,
                ),
                ToolCall(
                    id = "debug-snapshot",
                    name = "ui_control",
                    argumentsJson = """{"action":"snapshot"}""",
                    status = ToolCallStatus.DONE,
                ),
                ToolCall(
                    id = "debug-sequence",
                    name = "ui_control",
                    argumentsJson = """{"action":"sequence","steps":[""" +
                        """{"action":"tap","text":"Search settings"},""" +
                        """{"action":"type","value":"battery","submit":true}]}""",
                    status = ToolCallStatus.DONE,
                ),
                ToolCall(
                    id = "debug-memory",
                    name = "core_memory_update",
                    argumentsJson = """{"slot":"context","set":"x"}""",
                    status = ToolCallStatus.DONE,
                ),
                ToolCall(
                    id = "debug-plan",
                    name = "plan_update",
                    argumentsJson = """{"steps":[]}""",
                    status = ToolCallStatus.DONE,
                ),
            )
            val first = runCatching { skills.saveAutoFromToolCalls("Debug flow", calls) }
            val second = runCatching { skills.saveAutoFromToolCalls("Debug flow", calls) }
            val saved = first.getOrNull()
            Log.i(
                TAG,
                "first=${first.isSuccess} id=${saved?.id} name=${saved?.name} " +
                    "duplicate=${second.getOrNull() == null}",
            )
            Log.i(TAG, "steps=${saved?.tools?.firstOrNull()?.steps?.map { it.tool }}")
            val listed = runCatching { skills.list() }.getOrDefault(emptyList())
            Log.i(TAG, "listed=${listed.map { it.id }}")
            saved?.let { runCatching { skills.delete(it.id) } }
            Log.i(TAG, "deleted=${saved?.id}")
            finish()
        }
    }

    private companion object {
        const val TAG = "BAIC2SK"
    }
}
