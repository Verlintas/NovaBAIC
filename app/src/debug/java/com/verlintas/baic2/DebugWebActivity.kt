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
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.tools.ToolContext
import com.verlintas.baic2.tools.ToolRegistry
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Debug-only smoke test for the app-owned WebView research tool. */
@AndroidEntryPoint
class DebugWebActivity : ComponentActivity() {

    @Inject
    lateinit var registry: ToolRegistry

    @Inject
    lateinit var toolContext: ToolContext

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val action = intent.getStringExtra("action")?.trim()?.lowercase().orEmpty()
                .ifEmpty { "open" }
            val url = intent.getStringExtra("url")?.trim().orEmpty()
                .ifEmpty { "https://github.com/search?q=openai&type=repositories" }
            val ref = intent.getStringExtra("click_ref")?.trim().orEmpty()
                .ifEmpty { intent.getStringExtra("ref")?.trim().orEmpty() }
            val tool = registry.tool("web_research")
            if (tool == null) {
                Log.e(TAG, "web_research is not registered")
                finish()
                return@launch
            }
            val arguments = buildJsonObject {
                put("action", action)
                if (action == "open") put("url", url)
                if (ref.isNotEmpty()) put("ref", ref)
            }
            Log.i(TAG, "action=$action url=$url ref=$ref extras=${intent.extras?.keySet()}")
            suspend fun execute(args: kotlinx.serialization.json.JsonObject): String {
                val startedAt = System.currentTimeMillis()
                val result = runCatching { tool.execute(args, toolContext) }
                Log.i(TAG, "elapsedMs=${System.currentTimeMillis() - startedAt} result=$result")
                return when (val value = result.getOrNull()) {
                    is ToolResult.Success -> value.output
                    is ToolResult.Failure -> "FAILURE: ${value.reason}"
                    is ToolResult.Denied -> "DENIED: ${value.reason}"
                    null -> "CRASH: ${result.exceptionOrNull()?.message}"
                }
            }
            var text = execute(arguments)
            if (action == "open" && ref.isNotEmpty()) {
                val clickArguments = buildJsonObject {
                    put("action", "click")
                    put("ref", ref)
                }
                text += "\n\n--- CLICK $ref ---\n" + execute(clickArguments)
            }
            File(getExternalDir(), "debug-web.txt").writeText(text)
            finish()
        }
    }

    private fun getExternalDir(): File =
        getExternalFilesDir(null) ?: filesDir

    private companion object {
        const val TAG = "BAIC2WEBRESEARCH"
    }
}
