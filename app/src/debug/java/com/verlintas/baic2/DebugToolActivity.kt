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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.tools.ToolContext
import com.verlintas.baic2.tools.ToolRegistry
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Debug-only runner for any registered tool: --es tool take_screenshot --es json {...} */
@AndroidEntryPoint
class DebugToolActivity : ComponentActivity() {

    @Inject
    lateinit var registry: ToolRegistry

    @Inject
    lateinit var ocr: OcrProvider

    @Inject
    lateinit var screenshot: ScreenshotProvider

    @Inject
    lateinit var accessibility: AccessibilityBridge

    private var pendingName: String? = null
    private var pendingArgs: JsonObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("tool")?.trim().orEmpty().ifEmpty { "screen_ocr" }
        val raw = intent.getStringExtra("json")?.trim().orEmpty().ifEmpty { "{}" }
        val args = runCatching {
            Json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull()
        if (args == null) {
            Log.e(TAG, "bad json: $raw")
            finish()
            return
        }
        if (registry.tool(name) == null) {
            Log.e(TAG, "tool not found: $name")
            finish()
            return
        }
        pendingName = name
        pendingArgs = args
        if (intent.getBooleanExtra("request_capture", false)) {
            startActivityForResult(screenshot.createPermissionIntent(), REQUEST_CAPTURE)
        } else {
            runTool()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CAPTURE) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null ||
            !screenshot.onPermissionResult(resultCode, data)
        ) {
            Log.e(TAG, "screen capture permission denied")
            finish()
            return
        }
        runTool()
    }

    private fun runTool() {
        val name = pendingName ?: return
        val args = pendingArgs ?: return
        val tool = registry.tool(name) ?: return
        lifecycleScope.launch {
            val context = ToolContext(
                appContext = applicationContext,
                permissions = { true },
                screenshot = screenshot,
                ocr = ocr,
                accessibility = accessibility,
            )
            val startedAt = System.currentTimeMillis()
            val result = runCatching { tool.execute(args, context) }
            val summary = when (val value = result.getOrNull()) {
                is ToolResult.Success ->
                    "Success(images=${value.images.size}, output=${value.output.take(1_000)})"
                is ToolResult.Failure ->
                    "Failure(reason=${value.reason.take(500)}, recoverable=${value.recoverable})"
                is ToolResult.Denied -> "Denied(reason=${value.reason.take(500)})"
                null -> "CRASH: ${result.exceptionOrNull()?.message}"
            }
            Log.i(
                TAG,
                "tool=$name elapsedMs=${System.currentTimeMillis() - startedAt} $summary",
            )
            val text = when (val value = result.getOrNull()) {
                is ToolResult.Success -> value.output
                is ToolResult.Failure -> "FAILURE: ${value.reason}"
                is ToolResult.Denied -> "DENIED: ${value.reason}"
                null -> "CRASH: ${result.exceptionOrNull()?.message}"
            }
            File(getExternalFilesDir(null), "debug-tool.txt").writeText(text)
            finish()
        }
    }

    private companion object {
        const val TAG = "BAIC2TOOL"
        const val REQUEST_CAPTURE = 7101
    }
}
