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
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.tools.ToolContext
import com.verlintas.baic2.tools.ToolRegistry
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Debug-only smoke test for ui_control snapshot/sequence on a real window. */
@AndroidEntryPoint
class DebugUiActivity : ComponentActivity() {

    @Inject
    lateinit var registry: ToolRegistry

    @Inject
    lateinit var ocr: OcrProvider

    @Inject
    lateinit var screenshot: ScreenshotProvider

    @Inject
    lateinit var accessibility: AccessibilityBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val launchPackage = intent.getStringExtra("launch")?.trim().orEmpty()
            if (launchPackage.isNotEmpty()) {
                packageManager.getLaunchIntentForPackage(launchPackage)?.let(::startActivity)
                delay(1_500)
            }
            val raw = intent.getStringExtra("json")?.trim().orEmpty()
                .ifEmpty { """{"action":"snapshot"}""" }
            val arguments = runCatching {
                Json.parseToJsonElement(raw) as? JsonObject
            }.getOrNull()
            if (arguments == null) {
                Log.e(TAG, "bad json: $raw")
                finish()
                return@launch
            }
            val tool = registry.tool("ui_control")
            if (tool == null) {
                Log.e(TAG, "ui_control is not registered")
                finish()
                return@launch
            }
            Log.i(TAG, "json=$raw")
            val context = ToolContext(
                appContext = applicationContext,
                permissions = { true },
                screenshot = screenshot,
                ocr = ocr,
                accessibility = accessibility,
            )
            val startedAt = System.currentTimeMillis()
            val result = runCatching { tool.execute(arguments, context) }
            Log.i(TAG, "elapsedMs=${System.currentTimeMillis() - startedAt} result=$result")
            val text = when (val value = result.getOrNull()) {
                is ToolResult.Success -> value.output
                is ToolResult.Failure -> "FAILURE: ${value.reason}"
                is ToolResult.Denied -> "DENIED: ${value.reason}"
                null -> "CRASH: ${result.exceptionOrNull()?.message}"
            }
            File(getExternalFilesDir(null), "debug-ui.txt").writeText(text)
            finish()
        }
    }

    private companion object {
        const val TAG = "BAIC2UI"
    }
}
