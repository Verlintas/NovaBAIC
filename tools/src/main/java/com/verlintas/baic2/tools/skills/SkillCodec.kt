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

package com.verlintas.baic2.tools.skills

import com.verlintas.baic2.core.model.Skill
import com.verlintas.baic2.core.model.SkillStep
import com.verlintas.baic2.core.model.SkillToolDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * YAML skill manifests: name/description/instructions plus declarative tool
 * recipes. Parsing is defensive — a bad file yields an actionable error, not
 * a crash.
 */
object SkillCodec {

    private val json = Json { explicitNulls = false }

    fun parse(yamlText: String): Result<Skill> = runCatching {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml().load<Any?>(yamlText) as? Map<String, Any?>
            ?: error("skill.yaml must be a mapping")
        val id = (root["id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: error("missing 'id'")
        val name = (root["name"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: error("missing 'name'")
        val version = (root["version"] as? Number)?.toInt() ?: 1
        val description = (root["description"] as? String).orEmpty()
        val instructions = (root["instructions"] as? String).orEmpty()
        val permissions = (root["permissions"] as? List<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()

        val rawTools = root["tools"] as? List<*> ?: emptyList<Any?>()
        val tools = rawTools.mapNotNull { element ->
            val tool = element as? Map<*, *> ?: return@mapNotNull null
            val toolId = (tool["id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val toolDescription = (tool["description"] as? String).orEmpty()
            val steps = (tool["steps"] as? List<*>).orEmpty().mapNotNull { stepElement ->
                val step = stepElement as? Map<*, *> ?: return@mapNotNull null
                val toolName = (step["tool"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                SkillStep(
                    tool = toolName,
                    argsJson = toJsonElement(step["args"]).toString(),
                )
            }
            if (steps.isEmpty()) null else SkillToolDef(
                id = toolId,
                description = toolDescription,
                steps = steps,
                parametersJson = tool["parameters"]?.let { toJsonElement(it).toString() }
                    ?: """{"type":"object","properties":{}}""",
            )
        }

        Skill(
            id = id,
            name = name,
            version = version,
            description = description,
            instructions = instructions,
            permissions = permissions,
            tools = tools,
        )
    }

    fun encode(skill: Skill): String {
        val root = linkedMapOf<String, Any?>(
            "id" to skill.id,
            "name" to skill.name,
            "version" to skill.version,
            "description" to skill.description,
            "permissions" to skill.permissions,
            "tools" to skill.tools.map { tool ->
                linkedMapOf(
                    "id" to tool.id,
                    "description" to tool.description,
                    "parameters" to toPlain(json.parseToJsonElement(tool.parametersJson)),
                    "steps" to tool.steps.map { step ->
                        linkedMapOf(
                            "tool" to step.tool,
                            "args" to toPlain(json.parseToJsonElement(step.argsJson)),
                        )
                    },
                )
            },
        ).apply {
            if (skill.instructions.isNotBlank()) put("instructions", skill.instructions)
        }
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
        }
        return Yaml(options).dump(root)
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(
            value.entries.associate { (key, entryValue) ->
                key.toString() to toJsonElement(entryValue)
            },
        )
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun toPlain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            element.content.toLongOrNull() != null -> element.content.toLong()
            element.content.toDoubleOrNull() != null -> element.content.toDouble()
            else -> element.content
        }
        is JsonArray -> element.map { toPlain(it) }
        is JsonObject -> element.mapValues { toPlain(it.value) }
    }
}
