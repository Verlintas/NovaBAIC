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

package com.verlintas.baic2.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Skill recipes can carry {{name}} placeholders in their step arguments;
 * [render] substitutes the caller's parameters so one recorded sequence
 * serves many inputs. Missing parameters fail with an actionable message
 * instead of executing a half-filled recipe.
 */
object SkillArgs {

    private val PLACEHOLDER = Regex("\\{\\{(\\w+)\\}\\}")

    fun render(argsJson: String, parameters: JsonObject): Result<String> {
        val element = runCatching {
            Json.parseToJsonElement(argsJson.ifBlank { "{}" })
        }.getOrNull() ?: return Result.success(argsJson.ifBlank { "{}" })
        val missing = LinkedHashSet<String>()
        val rendered = substitute(element, parameters, missing)
        if (missing.isNotEmpty()) {
            return Result.failure(
                IllegalArgumentException("missing parameter(s): ${missing.joinToString(", ")}"),
            )
        }
        return Result.success(Json.encodeToString(JsonElement.serializer(), rendered))
    }

    private fun substitute(
        element: JsonElement,
        parameters: JsonObject,
        missing: MutableSet<String>,
    ): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (_, value) -> substitute(value, parameters, missing) },
        )

        is JsonArray -> JsonArray(element.map { substitute(it, parameters, missing) })

        is JsonPrimitive -> substitutePrimitive(element, parameters, missing)

        else -> element
    }

    private fun substitutePrimitive(
        primitive: JsonPrimitive,
        parameters: JsonObject,
        missing: MutableSet<String>,
    ): JsonElement {
        if (!primitive.isString) return primitive
        val text = primitive.content
        // A whole-token placeholder keeps the parameter's own JSON type.
        PLACEHOLDER.matchEntire(text)?.let { match ->
            val key = match.groupValues[1]
            return parameters[key] ?: run {
                missing += key
                primitive
            }
        }
        return JsonPrimitive(
            PLACEHOLDER.replace(text) { match ->
                val key = match.groupValues[1]
                val value = parameters[key]
                if (value == null) {
                    missing += key
                    match.value
                } else {
                    (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
                }
            },
        )
    }
}
