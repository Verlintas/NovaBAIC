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

import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.device.api.TextNode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/**
 * Fuzzy match score for a visible label: 0 exact, 1 normalized-exact,
 * 2 prefix, 3 substring, 4 label-inside-query, 5 edit distance <= 2.
 * Null means no reasonable match.
 */
internal fun uiMatchScore(label: String, query: String): Int? {
    val normalizedLabel = label.trim().lowercase()
    val normalizedQuery = query.trim().lowercase()
    if (normalizedLabel.isEmpty() || normalizedQuery.isEmpty()) return null
    if (normalizedLabel == normalizedQuery) return 0
    val stripped = { value: String -> value.replace(Regex("[\\p{Punct}\\s]+"), "") }
    if (stripped(normalizedLabel) == stripped(normalizedQuery)) return 1
    if (normalizedLabel.startsWith(normalizedQuery)) return 2
    if (normalizedLabel.contains(normalizedQuery)) return 3
    if (normalizedQuery.contains(normalizedLabel)) return 4
    if (normalizedQuery.length >= 3 && uiDistance(normalizedLabel, normalizedQuery) <= 2) return 5
    return null
}

/** Best matches first, stable for equal scores. */
internal fun uiBestMatches(nodes: List<TextNode>, query: String, limit: Int = 10): List<TextNode> =
    nodes
        .mapNotNull { node -> uiMatchScore(node.text, query)?.let { node to it } }
        .sortedBy { it.second }
        .map { it.first }
        .distinctBy { "${it.text}@${it.centerX},${it.centerY}" }
        .take(limit)

internal fun uiDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

/**
 * One smart UI tool instead of five dumb ones: locating (with scroll-to-find
 * and OCR fallback), tapping, typing, keys, scrolling, waiting and screen
 * text all live here. Tap/long-press resolve their target and verify that the
 * screen actually changed, so the model rarely needs a follow-up call.
 */
class UiControlTool : DeviceTool {

    override val spec = ToolSpec(
        name = "ui_control",
        description = "Control the UI through accessibility. snapshot labels interactive elements " +
            "with short refs (e1, e2, ...), then tap/long_press by ref or text (fuzzy match, " +
            "scrolls to find it, verifies the screen changed). Also: type into the focused field " +
            "(optional submit), press_key, scroll, wait_for, assert_text, read_text, find, or " +
            "screen_text. Use action=sequence with as many steps as the flow needs (5-minute " +
            "safety budget) to run a deterministic flow in one call; it stops on the first " +
            "failed step. assert_text ignores text already sitting in input fields.",
        parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["tap","long_press","type","press_key","scroll","wait_for","find","screen_text","snapshot","assert_text","read_text","sequence"]},"text":{"type":"string","description":"target text for tap/long_press/wait_for/assert_text/find"},"ref":{"type":"string","description":"element ref from the latest snapshot, e.g. e3"},"x":{"type":"integer"},"y":{"type":"integer"},"value":{"type":"string","description":"text to type, or the key for press_key"},"submit":{"type":"boolean","description":"press enter after typing"},"direction":{"type":"string","enum":["up","down","left","right"]},"distance":{"type":"integer","description":"scroll distance in px"},"timeout_ms":{"type":"integer","description":"wait_for/assert_text timeout, default 6000/4000"},"max_chars":{"type":"integer","description":"read_text character limit, default 2000, max 6000"},"steps":{"type":"array","description":"ordered steps for action=sequence (at least 2)","items":{"type":"object","properties":{"action":{"type":"string","enum":["tap","long_press","type","press_key","scroll","wait_for","find","screen_text","snapshot","assert_text","read_text"]},"text":{"type":"string"},"ref":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"},"value":{"type":"string"},"submit":{"type":"boolean"},"direction":{"type":"string","enum":["up","down","left","right"]},"distance":{"type":"integer"},"timeout_ms":{"type":"integer"},"max_chars":{"type":"integer"}},"required":["action"]}}},"required":["action"]}""",
        readOnly = false,
        danger = DangerLevel.MEDIUM,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val action = (arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            ?: return ToolResult.Failure("Missing 'action' argument")
        return runAction(action, arguments, context)
    }

    private suspend fun runAction(
        action: String,
        arguments: JsonObject,
        context: ToolContext,
    ): ToolResult = when (action) {
        "tap" -> pointerAction(context, arguments, long = false)
        "long_press" -> pointerAction(context, arguments, long = true)
        "type" -> typeAction(context, arguments)
        "press_key" -> pressKeyAction(context, arguments)
        "scroll" -> scrollAction(context, arguments)
        "wait_for" -> waitAction(context, arguments)
        "find" -> findAction(context, arguments)
        "screen_text" -> screenTextAction(context)
        "snapshot" -> snapshotAction(context)
        "assert_text" -> assertTextAction(context, arguments)
        "read_text" -> readTextAction(context, arguments)
        "sequence" -> sequenceAction(context, arguments)
        else -> ToolResult.Failure(
            "Unknown action '$action'. Use tap|long_press|type|press_key|scroll|wait_for|" +
                "find|screen_text|snapshot|assert_text|read_text|sequence.",
        )
    }

    private suspend fun sequenceAction(
        context: ToolContext,
        arguments: JsonObject,
    ): ToolResult {
        val steps = arguments["steps"] as? JsonArray
            ?: return ToolResult.Failure("Missing 'steps' for action=sequence")
        if (steps.size < 2) {
            return ToolResult.Failure("Sequence needs at least 2 steps.")
        }
        val objects = steps.mapIndexed { index, element ->
            element as? JsonObject
                ?: return ToolResult.Failure("Sequence step ${index + 1} is not an object.")
        }
        return runSequence(objects) { action, step -> runAction(action, step, context) }
    }

    internal suspend fun runSequence(
        steps: List<JsonObject>,
        runStep: suspend (String, JsonObject) -> ToolResult,
    ): ToolResult {
        val summaries = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + MAX_SEQUENCE_DURATION_MS
        steps.forEachIndexed { index, step ->
            if (System.currentTimeMillis() > deadline) {
                return ToolResult.Failure(
                    "Sequence timed out after ${MAX_SEQUENCE_DURATION_MS}ms at step ${index + 1}.",
                )
            }
            val action = (step["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: return ToolResult.Failure("Sequence step ${index + 1} is missing 'action'.")
            if (action == "sequence") {
                return ToolResult.Failure("Sequence steps cannot nest another sequence.")
            }
            // A wait/assert may not outlive the sequence budget: clamp its own
            // timeout to the time left so a stuck step cannot run for minutes.
            val effectiveStep = if (action == "wait_for" || action == "assert_text") {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                val requested = (step["timeout_ms"] as? JsonPrimitive)?.intOrNull
                val bounded = (requested ?: if (action == "wait_for") 6_000 else 4_000)
                    .coerceAtMost(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                JsonObject(step + ("timeout_ms" to JsonPrimitive(bounded)))
            } else {
                step
            }
            when (val result = runStep(action, effectiveStep)) {
                is ToolResult.Success -> {
                    val cap = when (action) {
                        "read_text", "screen_text" -> STEP_TEXT_LIMIT
                        "snapshot" -> SNAPSHOT_STEP_LIMIT
                        else -> STEP_SUMMARY_LIMIT
                    }
                    summaries += "${index + 1}. $action: ${result.output.take(cap)}"
                }
                is ToolResult.Failure ->
                    return ToolResult.Failure(
                        buildString {
                            append("Sequence stopped at step ").append(index + 1)
                            append(" (").append(action).append("): ").append(result.reason)
                            if (summaries.isNotEmpty()) {
                                append("\nCompleted: ").append(summaries.joinToString(" | "))
                            }
                        }.take(SEQUENCE_OUTPUT_LIMIT),
                    )
                is ToolResult.Denied -> return result
            }
        }
        return ToolResult.Success(
            ("Sequence completed (${steps.size} steps):\n" + summaries.joinToString("\n"))
                .take(SEQUENCE_OUTPUT_LIMIT),
        )
    }

    // ------------------------------------------------------------------ tap

    private suspend fun pointerAction(
        context: ToolContext,
        arguments: JsonObject,
        long: Boolean,
    ): ToolResult {
        val text = (arguments["text"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
        val ref = (arguments["ref"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
        val x = (arguments["x"] as? JsonPrimitive)?.intOrNull
        val y = (arguments["y"] as? JsonPrimitive)?.intOrNull

        val target: Pair<Int, Int>
        val matchNote: String
        if (ref != null) {
            val entry = UiRefRegistry.find(ref)
                ?: return ToolResult.Failure(
                    "Ref '$ref' is unknown, expired, or from an older snapshot. " +
                        "Call action=snapshot again and use the new refs.",
                )
            val currentPackage = context.accessibility.foregroundPackage()
            if (entry.packageName != null && currentPackage != null &&
                entry.packageName != currentPackage
            ) {
                return ToolResult.Failure(
                    "Ref '$ref' belongs to ${entry.packageName}, but $currentPackage is in front. " +
                        "Call action=snapshot again on the current screen.",
                )
            }
            val fresh = resolveRef(context, entry)
                ?: return ToolResult.Failure(
                    "Ref '$ref' (\"${entry.node.text}\") no longer matches the screen. " +
                        "Call action=snapshot again before tapping.",
                )
            target = fresh.centerX to fresh.centerY
            matchNote = "ref=$ref match=\"${fresh.text}\""
        } else if (text != null) {
            val resolved = resolveTextTarget(context, text)
                ?: return ToolResult.Failure(notFoundMessage(context, text))
            target = resolved.first.centerX to resolved.first.centerY
            matchNote = "match=\"${resolved.first.text}\"${if (resolved.second) ", scrolled into view" else ""}"
        } else if (x != null && y != null) {
            target = x to y
            matchNote = "coordinates"
        } else {
            return ToolResult.Failure("Provide 'ref', 'text', or both 'x' and 'y'")
        }

        val before = screenSignature(context)
        val gesture = if (long) {
            context.accessibility.longPress(target.first, target.second)
        } else {
            context.accessibility.tap(target.first, target.second)
        }
        val failure = gesture.exceptionOrNull()
        if (failure != null) return accessibilityFailure(failure)

        delay(SETTLE_DELAY_MS)
        val after = screenSignature(context)
        val changed = before != after
        val summary = buildString {
            append(if (long) "Long-pressed " else "Tapped ")
            append("(${target.first}, ${target.second}) [$matchNote]. ")
            append(if (changed) "Screen changed:" else "Screen unchanged:")
            append(' ').append(after)
        }
        return ToolResult.Success(summary)
    }

    /**
     * Prefer the active window so a background copy of the same label cannot
     * steal a tap; fall back to every application window for split-window UIs
     * (Settings search delegates to another package on some releases).
     */
    private fun findNodes(context: ToolContext, query: String): List<TextNode> {
        val active = context.accessibility.findText(query)
        return active.ifEmpty { context.accessibility.findTextAllWindows(query) }
    }

    private fun findNodesEverywhere(context: ToolContext, query: String): List<TextNode> {
        val active = context.accessibility.findText(query)
        val all = context.accessibility.findTextAllWindows(query)
        return (active + all).distinctBy { "${it.text}@${it.centerX},${it.centerY}" }
    }

    /**
     * Finds the best visible match, scrolling down (then restoring position)
     * when it is not on screen yet. Accessibility tree first, OCR as fallback.
     */
    private suspend fun resolveTextTarget(context: ToolContext, query: String): Pair<TextNode, Boolean>? {
        bestMatch(context, query)?.let { return it to false }

        val scrolls = mutableListOf<Int>()
        repeat(MAX_SCROLLS) { index ->
            val travelled = scrollBy(context, "down", null) ?: return@repeat
            scrolls += travelled
            delay(SCROLL_SETTLE_MS)
            bestMatch(context, query)?.let { return it to true }
        }
        // Not found: put the screen back where it was so the model keeps its map.
        scrolls.reversed().forEach { distance ->
            scrollBy(context, "up", distance)?.let { delay(SCROLL_SETTLE_MS) }
        }
        return null
    }

    /**
     * A ref is only valid while the same label still exists at (roughly) the
     * same place. Re-reading the node avoids tapping a recycled coordinate
     * after the list scrolled or a new screen loaded.
     */
    private fun resolveRef(context: ToolContext, entry: UiRefEntry): TextNode? {
        val cached = entry.node
        val candidates = buildList {
            if (cached.editable) context.accessibility.editableNode()?.let(::add)
            addAll(findNodesEverywhere(context, cached.text))
        }.filter {
            abs(it.centerX - cached.centerX) <= REF_SLOP_PX &&
                abs(it.centerY - cached.centerY) <= REF_SLOP_PX
        }
        return candidates.minByOrNull {
            abs(it.centerX - cached.centerX) + abs(it.centerY - cached.centerY)
        }
    }

    private suspend fun bestMatch(context: ToolContext, query: String): TextNode? {
        uiBestMatches(findNodes(context, query), query, limit = 1).firstOrNull()?.let { return it }
        val bytes = context.screenshot.capture().getOrNull() ?: return null
        val lines = context.ocr.recognize(bytes).getOrNull() ?: return null
        return uiBestMatches(
            lines.map { TextNode(it.text, it.left, it.top, it.right, it.bottom) },
            query,
            limit = 1,
        ).firstOrNull()
    }

    private fun notFoundMessage(context: ToolContext, query: String): String {
        val visible = context.accessibility.screenText(30).lines().filter { it.isNotBlank() }.take(12)
        return buildString {
            append("'").append(query).append("' was not found on screen")
            if (visible.isNotEmpty()) {
                append(". Visible now: ").append(visible.joinToString(" | "))
                append(". Use ui_control find with a shorter keyword or screen_text to inspect.")
            } else {
                append(". Accessibility service may be disabled; check Settings → Accessibility.")
            }
        }
    }

    // ----------------------------------------------------------------- type

    private suspend fun typeAction(context: ToolContext, arguments: JsonObject): ToolResult {
        val value = (arguments["value"] as? JsonPrimitive)?.content
            ?: return ToolResult.Failure("Missing 'value' (text to type)")
        var lastFailure: Throwable? = null
        repeat(TYPE_ATTEMPTS) { attempt ->
            if (!context.accessibility.editableFocused()) {
                if (!focusSearchField(context)) {
                    delay(TYPE_RETRY_DELAY_MS)
                    return@repeat
                }
            }
            val typed = context.accessibility.typeText(value)
            if (typed.isSuccess) {
                return submitTyped(context, value, arguments)
            }
            lastFailure = typed.exceptionOrNull()
            delay(TYPE_RETRY_DELAY_MS)
        }
        val detail = lastFailure?.message ?: "no focused input"
        return ToolResult.Failure(
            "Could not enter text ($detail). The field may still be loading: wait_for the field " +
                "or tap it, then retry. Do not retry this step blindly.",
        )
    }

    /** Tries to focus a visible search field when the previous tap had no effect yet. */
    private suspend fun focusSearchField(context: ToolContext): Boolean {
        val node = context.accessibility.findSearchNode() ?: return false
        val tapped = context.accessibility.tap(node.centerX, node.centerY).isSuccess
        if (tapped) delay(TYPE_RETRY_DELAY_MS)
        return tapped
    }

    private suspend fun submitTyped(
        context: ToolContext,
        value: String,
        arguments: JsonObject,
    ): ToolResult {
        val submit = (arguments["submit"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!submit) return ToolResult.Success("Typed ${value.length} characters.")
        val before = context.accessibility.screenText(100)
        val enter = context.accessibility.pressKey("enter")
        if (enter.isFailure) {
            return ToolResult.Success(
                "Typed ${value.length} characters. Enter unavailable: ${enter.exceptionOrNull()?.message}",
            )
        }
        // A search field often navigates on enter; waiting for the first
        // content change keeps the next step from acting on the old list.
        val changed = context.accessibility.awaitContentChange(0, SUBMIT_WAIT_MS, before)
        return ToolResult.Success(
            "Typed ${value.length} characters and pressed enter." +
                if (changed) "" else " Screen did not change within ${SUBMIT_WAIT_MS}ms.",
        )
    }

    // ------------------------------------------------------------- key/scroll

    private suspend fun pressKeyAction(context: ToolContext, arguments: JsonObject): ToolResult {
        val key = (arguments["value"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            ?: (arguments["text"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            ?: return ToolResult.Failure("Missing 'value' (back|home|recents|notifications|enter)")
        val before = screenSignature(context)
        val result = context.accessibility.pressKey(key)
        result.exceptionOrNull()?.let { return accessibilityFailure(it) }
        delay(SETTLE_DELAY_MS)
        val after = screenSignature(context)
        return ToolResult.Success("Pressed '$key'. Now on: $after (was: $before)")
    }

    private suspend fun scrollAction(context: ToolContext, arguments: JsonObject): ToolResult {
        val direction = (arguments["direction"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            ?: return ToolResult.Failure("Missing 'direction' (up|down|left|right)")
        val distance = (arguments["distance"] as? JsonPrimitive)?.intOrNull
        scrollBy(context, direction, distance)
            ?: return ToolResult.Failure("Cannot scroll: accessibility service is not enabled.")
        delay(SCROLL_SETTLE_MS)
        val visible = context.accessibility.screenText(12).lines().filter { it.isNotBlank() }.take(5)
        return ToolResult.Success(
            "Scrolled $direction" +
                if (visible.isEmpty()) "." else ". Now visible: ${visible.joinToString(" | ")}",
        )
    }

    private suspend fun scrollBy(context: ToolContext, direction: String, distance: Int?): Int? {
        val (width, height) = context.accessibility.screenSize() ?: return null
        val cx = width / 2
        val cy = height / 2
        val travel = distance?.coerceIn(50, maxOf(height, width))
            ?: when (direction) {
                "up", "down" -> (height * 0.4f).toInt()
                else -> (width * 0.4f).toInt()
            }
        val (x1, y1, x2, y2) = when (direction) {
            "up" -> listOf(cx, cy - travel / 2, cx, cy + travel / 2)
            "down" -> listOf(cx, cy + travel / 2, cx, cy - travel / 2)
            "left" -> listOf(cx + travel / 2, cy, cx - travel / 2, cy)
            "right" -> listOf(cx - travel / 2, cy, cx + travel / 2, cy)
            else -> return null
        }
        val result = context.accessibility.swipe(x1, y1, x2, y2, 260)
        return if (result.isSuccess) travel else null
    }

    // -------------------------------------------------------------- wait/find

    private suspend fun waitAction(context: ToolContext, arguments: JsonObject): ToolResult {
        val query = (arguments["text"] as? JsonPrimitive)?.content?.trim()
            ?: return ToolResult.Failure("Missing 'text' to wait for")
        val timeout = ((arguments["timeout_ms"] as? JsonPrimitive)?.intOrNull ?: 6_000)
            .coerceIn(500, MAX_WAIT_MS)
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val match = uiBestMatches(findNodes(context, query), query, limit = 1).firstOrNull()
            if (match != null) {
                return ToolResult.Success("'${match.text}' appeared at (${match.centerX}, ${match.centerY}).")
            }
            delay(POLL_INTERVAL_MS)
        }
        return ToolResult.Failure(notFoundMessage(context, query) + " (waited ${timeout}ms)")
    }

    private suspend fun findAction(context: ToolContext, arguments: JsonObject): ToolResult {
        val query = (arguments["text"] as? JsonPrimitive)?.content?.trim()
            ?: return ToolResult.Failure("Missing 'text' to find")
        val treeMatches = uiBestMatches(findNodes(context, query), query, limit = 10)
        if (treeMatches.isNotEmpty()) {
            return ToolResult.Success(renderMatches(query, treeMatches, source = "accessibility"))
        }
        val bytes = context.screenshot.capture().getOrElse { return screenshotFailure(it) }
        val lines = context.ocr.recognize(bytes).getOrElse { error ->
            return ToolResult.Failure("OCR failed: ${error.message ?: "unknown error"}")
        }
        val ocrMatches = uiBestMatches(
            lines.map { TextNode(it.text, it.left, it.top, it.right, it.bottom) },
            query,
            limit = 10,
        )
        if (ocrMatches.isEmpty()) return ToolResult.Failure(notFoundMessage(context, query))
        return ToolResult.Success(renderMatches(query, ocrMatches, source = "OCR"))
    }

    private fun renderMatches(query: String, matches: List<TextNode>, source: String): String = buildString {
        append("Matches for '").append(query).append("' (").append(source).append(", best first):\n")
        matches.forEach { node ->
            append("\"").append(node.text).append("\" @ (").append(node.centerX)
                .append(", ").append(node.centerY).append(")\n")
        }
        append("Tip: action=tap with text=<match> resolves and taps it in one step.")
    }

    private suspend fun screenTextAction(context: ToolContext): ToolResult {
        val treeText = context.accessibility.screenText(150)
        if (treeText.isNotBlank()) {
            val clipped = treeText.take(SCREEN_TEXT_LIMIT)
            return ToolResult.Success("Screen text:\n$clipped")
        }
        val bytes = context.screenshot.capture().getOrElse { return screenshotFailure(it) }
        val lines = context.ocr.recognize(bytes).getOrElse { error ->
            return ToolResult.Failure("OCR failed: ${error.message ?: "unknown error"}")
        }
        if (lines.isEmpty()) return ToolResult.Success("No text detected on screen.")
        val text = lines.joinToString("\n") { it.text }.take(SCREEN_TEXT_LIMIT)
        return ToolResult.Success("Screen text (OCR, ${lines.size} lines):\n$text")
    }

    // ------------------------------------------------------------- snapshot

    private fun snapshotAction(context: ToolContext): ToolResult {
        val nodes = context.accessibility.snapshotNodes(MAX_SNAPSHOT_NODES)
        if (nodes.isEmpty()) {
            return ToolResult.Failure(
                "No labelled interactive elements were visible. The screen may still be loading, " +
                    "or Accessibility may be off.",
            )
        }
        val pkg = context.accessibility.foregroundPackage()
        val entries = UiRefRegistry.replace(nodes, pkg)
        val label = pkg ?: "current screen"
        return ToolResult.Success(
            buildString {
                append("Snapshot of ").append(label).append(" (")
                append(entries.size).append(" elements; refs expire in ")
                append(UiRefRegistry.TTL_MS / 1_000).append("s or on the next snapshot):\n")
                entries.forEach { entry ->
                    val node = entry.node
                    append('[').append(entry.id).append("] ")
                    append(node.text.take(SNAPSHOT_LABEL_LIMIT))
                    val kind = shortClassName(node.clickableClassName ?: node.className)
                    if (kind.isNotBlank()) append(" — ").append(kind)
                    val flags = buildList {
                        if (node.editable) add("input")
                        if (node.hasToggle) add("toggle")
                        if (!node.editable && node.clickableClassName != null) add("tap")
                    }
                    if (flags.isNotEmpty()) append(" (").append(flags.joinToString(",")).append(')')
                    append('\n')
                }
                append("Use tap/long_press with ref=<id>; take a new snapshot after the screen changes.")
            }.take(SNAPSHOT_OUTPUT_LIMIT),
        )
    }

    private fun shortClassName(value: String?): String =
        value?.substringAfterLast('.')?.takeIf { it.isNotBlank() }.orEmpty()

    // ----------------------------------------------------------- assertions

    private suspend fun assertTextAction(
        context: ToolContext,
        arguments: JsonObject,
    ): ToolResult {
        val query = (arguments["text"] as? JsonPrimitive)?.content?.trim()
            ?: return ToolResult.Failure("Missing 'text' to assert")
        val timeout = ((arguments["timeout_ms"] as? JsonPrimitive)?.intOrNull ?: ASSERT_TIMEOUT_MS)
            .coerceIn(0, MAX_WAIT_MS)
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            val match = uiBestMatches(
                // The search box still contains the query while results load;
                // counting it as an assertion would verify nothing.
                findNodes(context, query).filterNot { it.editable },
                query,
                limit = 1,
            ).firstOrNull()
            if (match != null) {
                return ToolResult.Success(
                    "Asserted \"${match.text}\" at (${match.centerX}, ${match.centerY}).",
                )
            }
            if (System.currentTimeMillis() >= deadline) break
            delay(POLL_INTERVAL_MS)
        }
        val visible = context.accessibility.screenText(30).lines()
            .filter { it.isNotBlank() }
            .take(8)
        return ToolResult.Failure(
            buildString {
                append("Assertion failed: \"").append(query).append("\" is not visible")
                if (visible.isNotEmpty()) append(" (now: ").append(visible.joinToString(" | ")).append(')')
                append(". Wait longer with timeout_ms, or re-read the screen and adjust the flow.")
            },
        )
    }

    // ------------------------------------------------------------ read text

    private suspend fun readTextAction(
        context: ToolContext,
        arguments: JsonObject,
    ): ToolResult {
        val maxChars = ((arguments["max_chars"] as? JsonPrimitive)?.intOrNull ?: READ_TEXT_LIMIT)
            .coerceIn(200, SCREEN_TEXT_LIMIT)
        val active = context.accessibility.screenText(200).trim()
        val tree = if (active.length >= 80) {
            active
        } else {
            context.accessibility.screenTextAllWindows(400).trim().ifBlank { active }
        }
        if (tree.isNotBlank()) return ToolResult.Success("Screen text:\n" + tree.take(maxChars))
        val bytes = context.screenshot.capture().getOrElse { return screenshotFailure(it) }
        val lines = context.ocr.recognize(bytes).getOrElse { error ->
            return ToolResult.Failure("OCR failed: ${error.message ?: "unknown error"}")
        }
        if (lines.isEmpty()) return ToolResult.Success("No text detected on screen.")
        val text = lines.joinToString("\n") { it.text }.take(maxChars)
        return ToolResult.Success("Screen text (OCR, ${lines.size} lines):\n$text")
    }

    private fun screenSignature(context: ToolContext): String {
        val pkg = context.accessibility.foregroundPackage() ?: "unknown"
        val title = context.accessibility.windowTitle()
        return if (title.isNullOrBlank()) pkg else "$pkg / $title"
    }

    private companion object {
        const val MAX_SCROLLS = 4
        const val SETTLE_DELAY_MS = 500L
        const val SCROLL_SETTLE_MS = 350L
        const val POLL_INTERVAL_MS = 400L
        const val SCREEN_TEXT_LIMIT = 6_000
        const val MAX_SEQUENCE_DURATION_MS = 300_000L
        const val MAX_WAIT_MS = 30_000
        const val ASSERT_TIMEOUT_MS = 4_000
        const val TYPE_ATTEMPTS = 3
        const val TYPE_RETRY_DELAY_MS = 350L
        const val SUBMIT_WAIT_MS = 1_500L
        const val REF_SLOP_PX = 32
        const val MAX_SNAPSHOT_NODES = 60
        const val SNAPSHOT_LABEL_LIMIT = 90
        const val SNAPSHOT_OUTPUT_LIMIT = 7_000
        const val READ_TEXT_LIMIT = 2_000
        const val STEP_SUMMARY_LIMIT = 300
        const val STEP_TEXT_LIMIT = 6_000
        const val SNAPSHOT_STEP_LIMIT = 5_000
        const val SEQUENCE_OUTPUT_LIMIT = 12_000
    }
}

private fun accessibilityFailure(error: Throwable): ToolResult.Failure {
    val message = error.message.orEmpty()
    return if (message.contains("accessibility_not_enabled")) {
        ToolResult.Failure(
            "Accessibility service is not enabled. Ask the user to turn on " +
                "Settings → Accessibility → BAIC2 界面自动化, then retry.",
        )
    } else {
        ToolResult.Failure("UI automation failed: ${message.ifBlank { "unknown" }}")
    }
}
