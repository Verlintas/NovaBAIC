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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Debug-only smoke test for app_research on the resident virtual display. */
@AndroidEntryPoint
class DebugResearchActivity : ComponentActivity() {

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
            val tool = registry.tool("app_research")
            if (tool == null) {
                Log.e(TAG, "app_research is not registered")
                finish()
                return@launch
            }
            val appArg = intent.getStringExtra("app")?.trim().orEmpty().ifEmpty { "Settings" }
            val queryArg = intent.getStringExtra("query")?.trim().orEmpty().ifEmpty { "battery" }
            val openArg = intent.getStringExtra("open")?.trim().orEmpty()
            val openTop = intent.getBooleanExtra("open_top", false)
            val sessionArg = intent.getStringExtra("session")?.trim().orEmpty()
            val maxScreens = intent.getIntExtra("max_screens", 1).coerceIn(1, 3)
            val arguments = buildJsonObject {
                put("app", appArg)
                put("query", queryArg)
                put("max_screens", maxScreens)
                if (openArg.isNotEmpty()) put("open", openArg)
                if (openTop) put("open_top", true)
                if (sessionArg.isNotEmpty()) put("session", sessionArg)
            }
            Log.i(
                TAG,
                "app=$appArg query=$queryArg open=$openArg open_top=$openTop session=$sessionArg",
            )
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
            File(getExternalFilesDir(null), "debug-research.txt").writeText(text)
            finish()
        }
    }

    private companion object {
        const val TAG = "BAIC2RS"
    }
}
