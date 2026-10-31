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

import com.verlintas.baic2.device.api.TextNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One stable element handle from the most recent snapshot. */
internal data class UiRefEntry(
    val id: String,
    val node: TextNode,
    val createdAtMs: Long,
    val packageName: String? = null,
)

/**
 * Short-lived `e1`, `e2`, ... handles for the last snapshot. Refs are replaced
 * on every snapshot and expire on their own, so they can never silently point
 * at a control from a different screen.
 */
internal object UiRefRegistry {

    const val TTL_MS = 300_000L
    private const val MAX_REFS = 100

    private val refs = LinkedHashMap<String, UiRefEntry>()

    @Synchronized
    fun replace(
        nodes: List<TextNode>,
        packageName: String? = null,
        now: Long = System.currentTimeMillis(),
    ): List<UiRefEntry> {
        refs.clear()
        nodes.take(MAX_REFS).forEachIndexed { index, node ->
            val id = "e${index + 1}"
            refs[id] = UiRefEntry(id, node, now, packageName)
        }
        return refs.values.toList()
    }

    @Synchronized
    fun find(id: String, now: Long = System.currentTimeMillis()): UiRefEntry? {
        pruneLocked(now)
        return refs[id.trim().lowercase()]
    }

    /** Label lookup for saving a recorded flow; ignores the tap TTL. */
    @Synchronized
    fun label(id: String): String? = refs[id.trim().lowercase()]?.node?.text

    @Synchronized
    fun clear() {
        refs.clear()
    }

    private fun pruneLocked(now: Long) {
        refs.entries.removeAll { now - it.value.createdAtMs > TTL_MS }
    }

    internal fun resetForTest() = clear()
}

/**
 * Rewrites `ref` arguments in a recorded `ui_control` call to concrete text,
 * so a skill saved after a snapshot does not depend on transient handles.
 * Unknown refs are left untouched: the replay then fails with the normal
 * "take a new snapshot" message instead of tapping a guessed coordinate.
 */
/** Reads `[e3] Label — Type` lines from a snapshot tool result. */
internal fun snapshotRefLabels(result: String?): Map<String, String> {
    if (result.isNullOrBlank()) return emptyMap()
    return SNAPSHOT_REF_LINE.findAll(result).associate { match ->
        val id = "e${match.groupValues[1]}"
        val label = match.groupValues[2]
            .substringBefore(" — ")
            .substringBefore(" (")
            .trim()
        id to label
    }.filterValues { it.isNotBlank() }
}

private val SNAPSHOT_REF_LINE = Regex("""\[e(\d+)]\s+(.+)""")

internal fun materializeUiRefs(
    argumentsJson: String,
    labelOf: (String) -> String? = UiRefRegistry::label,
): String {
    val root = runCatching {
        Json.parseToJsonElement(argumentsJson) as? JsonObject
    }.getOrNull() ?: return argumentsJson
    val rewritten = materializeObject(root, labelOf) ?: return argumentsJson
    return rewritten.toString()
}

private fun materializeObject(
    source: JsonObject,
    labelOf: (String) -> String?,
): JsonObject? {
    var changed = false
    val output = LinkedHashMap<String, JsonElement>()
    source.forEach { (key, value) ->
        if (key == "steps" && value is JsonArray) {
            val steps = value.map { element ->
                val step = element as? JsonObject ?: return@map element
                val rewritten = materializeObject(step, labelOf)
                if (rewritten != null) changed = true
                rewritten ?: step
            }
            output[key] = JsonArray(steps)
        } else {
            output[key] = value
        }
    }
    val ref = (source["ref"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
    if (ref != null) {
        val label = labelOf(ref)
        if (label != null) {
            output.remove("ref")
            output["text"] = JsonPrimitive(label)
            changed = true
        }
    }
    return if (changed) JsonObject(output) else null
}
