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

package com.verlintas.baic2.tools.web

import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.device.api.WebBrowser
import com.verlintas.baic2.device.api.WebSnapshot
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Read-only web research in an app-owned background browser. Unlike
 * app_research, this does not depend on another app's accessibility tree: it
 * reads the page DOM and clicks stable refs.
 */
class WebResearchTool @Inject constructor(
    private val browser: WebBrowser,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "web_research",
        untrustedOutput = true,
        description = "Read-only research in a background web browser. Open a web page, take a " +
            "DOM snapshot with stable element refs (w1, w2…), click a ref, type into an input, " +
            "or scroll. Prefer this over app_research when the target platform has a usable web " +
            "version (GitHub, X, Reddit, Zhihu…). It never sends messages, posts, orders or pays. " +
            "If a page needs login or a captcha, call 'show_to_user': it opens the same cookie " +
            "session in a foreground browser and waits for the user, then returns the post-login " +
            "snapshot. Use 'open' with an https URL, then 'snapshot', then 'click'/'read'. The " +
            "background session closes after 5 minutes idle; 'hide' closes it immediately.",
        parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["open","snapshot","read","click","type","scroll","show_to_user","hide"],"description":"browser action; hide closes the session"},"url":{"type":"string","description":"https URL for open; HTTP is only accepted for localhost/private LAN"},"ref":{"type":"string","description":"element ref from a snapshot, e.g. w3"},"text":{"type":"string","description":"text for the type action"},"direction":{"type":"string","enum":["up","down"],"description":"scroll direction, default down"}},"required":["action"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val action = (arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        if (action.isEmpty()) return ToolResult.Failure("Missing 'action' argument.")
        return when (action) {
            "open" -> {
                val url = (arguments["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (url.isEmpty()) return ToolResult.Failure("Missing 'url' for open.")
                browser.open(url).fold(
                    onSuccess = { ToolResult.Success(format(it, includeElements = true)) },
                    onFailure = { failure("open", it) },
                )
            }

            "snapshot" -> browser.snapshot().fold(
                onSuccess = { ToolResult.Success(format(it, includeElements = true)) },
                onFailure = { failure("snapshot", it) },
            )

            "read" -> browser.snapshot().fold(
                onSuccess = { ToolResult.Success(format(it, includeElements = false)) },
                onFailure = { failure("read", it) },
            )

            "click" -> {
                val ref = (arguments["ref"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (ref.isEmpty()) return ToolResult.Failure("Missing 'ref' for click.")
                browser.click(ref).fold(
                    onSuccess = { ToolResult.Success(format(it, includeElements = true)) },
                    onFailure = { failure("click $ref", it) },
                )
            }

            "type" -> {
                val ref = (arguments["ref"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val text = (arguments["text"] as? JsonPrimitive)?.content.orEmpty()
                if (ref.isEmpty()) return ToolResult.Failure("Missing 'ref' for type.")
                browser.type(ref, text).fold(
                    onSuccess = { ToolResult.Success(format(it, includeElements = true)) },
                    onFailure = { failure("type $ref", it) },
                )
            }

            "scroll" -> {
                val direction = (arguments["direction"] as? JsonPrimitive)?.content
                    ?.trim()?.lowercase()?.takeIf { it == "up" || it == "down" } ?: "down"
                browser.scroll(direction).fold(
                    onSuccess = { ToolResult.Success(format(it, includeElements = true)) },
                    onFailure = { failure("scroll", it) },
                )
            }

            "show_to_user" -> browser.showToUser().fold(
                onSuccess = {
                    browser.snapshot().fold(
                        onSuccess = { snapshot ->
                            ToolResult.Success(
                                "User handoff finished. Current page:\n\n" +
                                    format(snapshot, includeElements = true),
                            )
                        },
                        onFailure = { failure("snapshot_after_handoff", it) },
                    )
                },
                onFailure = { failure("show_to_user", it) },
            )

            "hide" -> browser.hide().fold(
                onSuccess = { ToolResult.Success("Browser session closed.") },
                onFailure = { failure("hide", it) },
            )

            else -> ToolResult.Failure(
                "Unknown action '$action'. Use open|snapshot|read|click|type|scroll|show_to_user|hide.",
            )
        }
    }

    private fun format(snapshot: WebSnapshot, includeElements: Boolean): String = buildString {
        append("URL: ").append(snapshot.url).append('\n')
        append("Title: ").append(snapshot.title).append('\n')
        if (includeElements) {
            append("\nInteractive elements (").append(snapshot.elements.size).append("):\n")
            snapshot.elements.take(60).forEach { element ->
                append("- ").append(element.ref)
                append(" [").append(element.tag)
                if (element.role.isNotBlank()) append('/').append(element.role)
                append("] ")
                append(element.label.ifBlank { "(no label)" }.take(120))
                element.href?.let { append(" -> ").append(it.take(200)) }
                if (element.editable) append("  (editable)")
                append('\n')
            }
        }
        val text = snapshot.text.trim()
        if (text.isNotBlank()) {
            append("\nPage text:\n")
            append(text.take(4_000))
            if (text.length > 4_000) append("\n…[truncated]")
        }
        append("\n\nRead-only: no message, post, like, follow, order or payment was made.")
    }.trimEnd()

    private fun failure(action: String, error: Throwable): ToolResult.Failure =
        ToolResult.Failure(
            "web_research $action failed: ${error.message ?: error.javaClass.simpleName}. " +
                "Check the URL/ref and try a fresh snapshot; if the page needs login or a " +
                "captcha, hand it to the user instead of guessing.",
        )
}
