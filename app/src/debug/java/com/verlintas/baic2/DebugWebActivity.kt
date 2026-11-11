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
import com.verlintas.baic2.device.api.WebBrowser
import com.verlintas.baic2.tools.ToolContext
import com.verlintas.baic2.tools.ToolRegistry
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Debug-only smoke test for the app-owned WebView research tool. */
@AndroidEntryPoint
class DebugWebActivity : ComponentActivity() {

    @Inject
    lateinit var registry: ToolRegistry

    @Inject
    lateinit var toolContext: ToolContext

    @Inject
    lateinit var browser: WebBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val action = intent.getStringExtra("action")?.trim()?.lowercase().orEmpty()
                .ifEmpty { "open" }
            val url = intent.getStringExtra("url")?.trim().orEmpty()
                .ifEmpty { "https://github.com/search?q=openai&type=repositories" }
            val ref = intent.getStringExtra("click_ref")?.trim().orEmpty()
                .ifEmpty { intent.getStringExtra("ref")?.trim().orEmpty() }
            val handoff = intent.getBooleanExtra("handoff", false)
            val reopen = intent.getBooleanExtra("reopen", false)
            val closeAfter = intent.getBooleanExtra("close_after", false)
            val clearAfter = intent.getBooleanExtra("clear_after", false)
            val waitText = intent.getStringExtra("wait_text")?.trim().orEmpty()
            val waitRef = intent.getStringExtra("wait_ref")?.trim().orEmpty()
            val waitTimeout = intent.getLongExtra("wait_timeout_ms", 5_000L)
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
            Log.i(TAG, "action=$action url=$url ref=$ref handoff=$handoff reopen=$reopen extras=${intent.extras?.keySet()}")
            suspend fun execute(args: kotlinx.serialization.json.JsonObject): String {
                val startedAt = System.currentTimeMillis()
                val result = runCatching { tool.execute(args, toolContext) }
                val summary = when (val value = result.getOrNull()) {
                    is ToolResult.Success ->
                        "Success(images=${value.images.size}, output=${value.output.take(300)})"
                    is ToolResult.Failure ->
                        "Failure(reason=${value.reason.take(300)}, recoverable=${value.recoverable})"
                    is ToolResult.Denied -> "Denied(reason=${value.reason.take(300)})"
                    null -> "CRASH: ${result.exceptionOrNull()?.message}"
                }
                Log.i(TAG, "elapsedMs=${System.currentTimeMillis() - startedAt} result=$summary")
                return when (val value = result.getOrNull()) {
                    is ToolResult.Success -> value.output
                    is ToolResult.Failure -> "FAILURE: ${value.reason}"
                    is ToolResult.Denied -> "DENIED: ${value.reason}"
                    null -> "CRASH: ${result.exceptionOrNull()?.message}"
                }
            }
            val stepsFile = intent.getStringExtra("steps_file")?.trim().orEmpty()
            if (stepsFile.isNotEmpty()) {
                val raw = runCatching { File(getExternalDir(), stepsFile).readText() }.getOrNull()
                val steps = raw?.let {
                    runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull()
                }
                if (steps == null) {
                    Log.e(TAG, "steps file missing or invalid: $stepsFile")
                    finish()
                    return@launch
                }
                var text = ""
                steps.forEachIndexed { index, element ->
                    val args = element.jsonObject
                    val stepAction = args["action"]?.jsonPrimitive?.content ?: "?"
                    text += "\n\n--- STEP ${index + 1} $stepAction ---\n" + execute(args)
                }
                File(getExternalDir(), "debug-web.txt").writeText(text)
                finish()
                return@launch
            }
            var text = execute(arguments)
            if (action == "open" && ref.isNotEmpty()) {
                val clickArguments = buildJsonObject {
                    put("action", "click")
                    put("ref", ref)
                }
                text += "\n\n--- CLICK $ref ---\n" + execute(clickArguments)
            }
            if (action == "open" && handoff) {
                val handoffArguments = buildJsonObject {
                    put("action", "show_to_user")
                }
                text += "\n\n--- HANDOFF ---\n" + execute(handoffArguments)
            }
            if (action == "open" && handoff && reopen) {
                val reopenArguments = buildJsonObject {
                    put("action", "open")
                    put("url", url)
                }
                text += "\n\n--- REOPEN ---\n" + execute(reopenArguments)
            }
            if (action == "open" && (waitText.isNotEmpty() || waitRef.isNotEmpty())) {
                val waitArguments = buildJsonObject {
                    put("action", "wait")
                    if (waitText.isNotEmpty()) put("text", waitText)
                    if (waitRef.isNotEmpty()) put("ref", waitRef)
                    put("timeout_ms", waitTimeout)
                }
                text += "\n\n--- WAIT ---\n" + execute(waitArguments)
            }
            if (action == "open" && closeAfter) {
                val hideArguments = buildJsonObject { put("action", "hide") }
                text += "\n\n--- HIDE ---\n" + execute(hideArguments)
                val snapshotArguments = buildJsonObject { put("action", "snapshot") }
                text += "\n\n--- AFTER HIDE ---\n" + execute(snapshotArguments)
            }
            if (action == "open" && clearAfter) {
                text += "\n\n--- CLEAR ---\n" + runCatching { browser.clearData() }
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
