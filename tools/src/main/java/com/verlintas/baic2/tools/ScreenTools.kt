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

package com.verlintas.baic2.tools

import android.content.Context
import android.util.Base64
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolImage
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.core.model.VisionModels
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** True when the active model is known to accept images or the call overrides it. */
private fun wantsVision(arguments: JsonObject, context: ToolContext): Boolean {
    val override = (arguments["vision"] as? JsonPrimitive)?.booleanOrNull
    return override ?: VisionModels.supports(context.run.config?.model)
}

private fun toolImage(
    bytes: ByteArray,
    fileName: String,
    localPath: String? = null,
): ToolImage = ToolImage(
    mimeType = "image/png",
    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
    fileName = fileName,
    localPath = localPath,
    width = 0,
    height = 0,
)

internal fun screenshotFailure(cause: Throwable?): ToolResult.Failure {
    val message = cause?.message.orEmpty()
    return if (message.contains("not_authorized")) {
        ToolResult.Failure(
            "Screen capture permission is missing. Ask the user to tap ⋮ → 分析屏幕 in the chat screen " +
                "and approve the system dialog.",
        )
    } else {
        ToolResult.Failure("Screen capture failed (${message.ifBlank { "unknown" }}). Try again.")
    }
}

class TakeScreenshotTool : DeviceTool {
    override val spec = ToolSpec(
        name = "take_screenshot",
        untrustedOutput = true,
        description = "Capture the current screen, save it as a PNG and return its path plus " +
            "OCR text. When the active model is a known vision family (or vision=true), the " +
            "image is also attached for the next model round. Avoid using this on password, " +
            "payment or verification screens.",
        parametersJson = """{"type":"object","properties":{"vision":{"type":"boolean","description":"attach the image for multimodal models; auto-detected when omitted"}}}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val bytes = context.screenshot.capture().getOrElse { return screenshotFailure(it) }
        return try {
            val directory = File(context.appContext.filesDir, "screenshots").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val file = File(directory, "screenshot_$stamp.png")
            file.writeBytes(bytes)
            val ocrText = runCatching {
                context.ocr.recognize(bytes).getOrNull()
                    ?.joinToString("\n") { it.text }
                    ?.trim()
                    .orEmpty()
            }.getOrDefault("")
            val vision = wantsVision(arguments, context)
            ToolResult.Success(
                buildString {
                    append("Screenshot saved: ").append(file.absolutePath)
                        .append(" (").append(bytes.size / 1024).append(" KB).")
                    if (vision) {
                        append(" The image is attached for the next model round.")
                    } else {
                        append(" Vision is off for this model; OCR text follows.")
                    }
                    if (ocrText.isNotBlank()) {
                        append("\n\nVisible text (OCR):\n").append(ocrText.take(6_000))
                    } else {
                        append("\n\nOCR found no visible text.")
                    }
                },
                images = if (vision) {
                    listOf(toolImage(bytes, "screenshot_$stamp.png", file.absolutePath))
                } else {
                    emptyList()
                },
            )
        } catch (e: Exception) {
            ToolResult.Failure("Could not save the screenshot: ${e.message}")
        }
    }
}

class ScreenOcrTool : DeviceTool {
    override val spec = ToolSpec(
        name = "screen_ocr",
        untrustedOutput = true,
        description = "One-shot screen perception: foreground app, window title, interactive " +
            "elements with coordinates (when accessibility is on), the full visible text, and " +
            "the image itself for known vision models (or vision=true). Prefer this over " +
            "separate app/OCR/find calls. Avoid password, payment or verification screens.",
        parametersJson = """{"type":"object","properties":{"vision":{"type":"boolean","description":"attach the image for multimodal models; auto-detected when omitted"}}}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val header = buildString {
            val pkg = context.accessibility.foregroundPackage()
            val title = context.accessibility.windowTitle()
            if (pkg != null) append("App: ").append(pkg)
            if (!title.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("Window: ").append(title)
            }
        }

        val interactive = context.accessibility.screenText(60)
            .lines()
            .filter { it.isNotBlank() }
            .distinct()
            .take(25)

        val bytes = context.screenshot.capture().getOrElse { return screenshotFailure(it) }
        val lines = context.ocr.recognize(bytes).getOrElse { error ->
            return ToolResult.Failure("OCR failed: ${error.message ?: "unknown error"}")
        }

        val vision = wantsVision(arguments, context)
        return ToolResult.Success(
            buildString {
                if (header.isNotEmpty()) append(header).append("\n\n")
                if (interactive.isNotEmpty()) {
                    append("Interactive/text elements (accessibility):\n")
                    interactive.forEach { append("- ").append(it).append('\n') }
                    append('\n')
                }
                if (lines.isEmpty()) {
                    append("No OCR text detected on screen.")
                } else {
                    val text = lines.joinToString("\n") { it.text }
                    val clipped = if (text.length > SCREEN_TEXT_LIMIT) {
                        text.take(SCREEN_TEXT_LIMIT) + "\n…(truncated)"
                    } else {
                        text
                    }
                    append("Visible text (OCR, ").append(lines.size).append(" lines):\n").append(clipped)
                }
                if (vision) {
                    append("\n\nThe screen image is attached for the next model round.")
                }
            },
            images = if (vision) {
                listOf(toolImage(bytes, "screenshot-screen-${System.currentTimeMillis()}.png"))
            } else {
                emptyList()
            },
        )
    }

    private companion object {
        const val SCREEN_TEXT_LIMIT = 6_000
    }
}

