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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Real models produce malformed tool arguments constantly: double-encoded
 * JSON, prose-wrapped payloads, misspelled keys, wrong primitive types.
 * Healing turns most of those into working calls; every fix is reported in
 * the tool result so nothing is hidden from the model.
 */
object ArgumentHealer {

    data class Healed(
        val arguments: JsonObject,
        val notes: List<String>,
    )

    /** Tolerant parse of a tool-call arguments string. */
    fun parse(raw: String): JsonObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        parseObject(trimmed)?.let { return it }

        // Double-encoded: the whole payload arrives as a JSON string.
        val inner = runCatching { Json.parseToJsonElement(trimmed) as? JsonPrimitive }
            .getOrNull()
            ?.takeIf { it.isString }
            ?.contentOrNull
        if (inner != null) parseObject(inner.trim())?.let { return it }

        // Prose-wrapped: "Sure! {\"city\":\"Beijing\"} let me know…".
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start in 0 until end) parseObject(trimmed.substring(start, end + 1))?.let { return it }
        return null
    }

    /** Repairs keys and primitive types against the tool's JSON schema. */
    fun heal(arguments: JsonObject, schemaJson: String): Healed {
        val properties = runCatching {
            (Json.parseToJsonElement(schemaJson) as? JsonObject)
                ?.get("properties") as? JsonObject
        }.getOrNull() ?: return Healed(arguments, emptyList())
        if (properties.isEmpty()) return Healed(arguments, emptyList())

        val notes = mutableListOf<String>()
        val schemaNames = properties.keys.toList()
        val healed = mutableMapOf<String, JsonElement>()

        // Explicitly valid keys win: a renamed key must never displace a value
        // the model already filed under the right name ({"cit":"A","city":"B"}).
        arguments.forEach { (key, value) ->
            if (key in properties) healed[key] = value
        }
        arguments.forEach { (key, value) ->
            if (key in properties) return@forEach
            // Case-only differences are exact renames; otherwise allow a
            // small typo only when exactly one schema key is close enough.
            // An ambiguous match could silently change the meaning.
            val exactCase = schemaNames.filter { candidate ->
                candidate != key && candidate.equals(key, ignoreCase = true)
            }
            val candidates = if (exactCase.size == 1) {
                exactCase
            } else {
                schemaNames.filter { candidate ->
                    val gap = distance(key.lowercase(), candidate.lowercase())
                    gap in 1..2 && candidate.lowercase() != key.lowercase()
                }
            }
            val target = candidates.singleOrNull()
            when {
                target != null && target !in healed -> {
                    healed[target] = value
                    notes += "$key→$target"
                }
                else -> if (key !in healed) healed[key] = value
            }
        }

        healed.entries.toList().forEach { (name, element) ->
            val expected = (properties[name] as? JsonObject)
                ?.get("type")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: return@forEach
            val coerced = coerce(element, expected) ?: return@forEach
            if (coerced != element) {
                healed[name] = coerced
                notes += "$name→$expected"
            }
        }
        return Healed(JsonObject(healed), notes)
    }

    private fun coerce(element: JsonElement, expected: String): JsonElement? {
        val primitive = element as? JsonPrimitive
        return when (expected) {
            "string" -> primitive?.takeIf { !it.isString }?.let { JsonPrimitive(it.content) }
            "integer" -> when {
                primitive == null -> null
                primitive.isString -> primitive.contentOrNull?.toLongOrNull()?.let(::JsonPrimitive)
                primitive.booleanOrNull != null -> JsonPrimitive(if (primitive.booleanOrNull == true) 1L else 0L)
                else -> null
            }
            "number" -> when {
                primitive == null -> null
                primitive.isString -> primitive.contentOrNull?.toDoubleOrNull()?.let(::JsonPrimitive)
                else -> null
            }
            "boolean" -> primitive?.contentOrNull?.lowercase()?.let {
                when (it) {
                    "true", "1", "yes" -> JsonPrimitive(true)
                    "false", "0", "no" -> JsonPrimitive(false)
                    else -> null
                }
            }
            "array" -> if (element !is JsonArray) JsonArray(listOf(element)) else null
            else -> null
        }
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
