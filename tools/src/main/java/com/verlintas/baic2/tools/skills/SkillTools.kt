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

import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.Skill
import com.verlintas.baic2.core.model.SkillArgs
import com.verlintas.baic2.core.model.SkillStep
import com.verlintas.baic2.core.model.SkillToolDef
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import com.verlintas.baic2.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Coroutine-context carrier for skill nesting depth (survives suspension). */
private class SkillNestingDepth(val depth: Int) :
    kotlin.coroutines.AbstractCoroutineContextElement(SkillNestingDepth) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SkillNestingDepth>
}

/** Replays a skill tool's step recipe through the registry. */
class CompositeSkillTool(
    private val skill: Skill,
    private val definition: SkillToolDef,
    private val danger: DangerLevel,
    /** True when any step tool produces untrusted external text. */
    private val untrustedOutput: Boolean,
    private val registry: () -> ToolRegistry,
) : DeviceTool {

    override val spec = ToolSpec(
        name = definition.id,
        description = "${definition.description} (skill: ${skill.name})",
        parametersJson = definition.parametersJson,
        readOnly = false,
        // Steps may call high-danger tools; surfacing the aggregate keeps the
        // confirmation gate in front of the whole recipe, not just the step.
        danger = maxOf(danger, DangerLevel.MEDIUM),
        // A recipe that reads untrusted text must taint the run like the step
        // would have, or injection rides through the skill untracked.
        untrustedOutput = untrustedOutput,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val depth = kotlin.coroutines.coroutineContext[SkillNestingDepth]?.depth ?: 0
        if (depth >= MAX_NESTING_DEPTH) {
            return ToolResult.Failure(
                "Skill nesting exceeded $MAX_NESTING_DEPTH levels (possible cycle); stopped.",
            )
        }
        return kotlinx.coroutines.withContext(SkillNestingDepth(depth + 1)) {
            runSteps(arguments, context)
        }
    }

    private suspend fun runSteps(arguments: JsonObject, context: ToolContext): ToolResult {
        val registryRef = registry()
        val lines = mutableListOf<String>()
        for ((index, step) in definition.steps.withIndex()) {
            if (step.tool == definition.id) {
                return ToolResult.Failure("Skill step ${index + 1} would recurse into itself; fix the recipe.")
            }
            val delegate = registryRef.tool(step.tool)
                ?: return ToolResult.Failure(
                    "Step ${index + 1} references unknown tool '${step.tool}'. " +
                        "Available: ${registryRef.toolNames.joinToString()}",
                )
            // {{placeholders}} in the recipe are filled from this call's args.
            val renderedJson = SkillArgs.render(step.argsJson, arguments).getOrElse { error ->
                return ToolResult.Failure(
                    "Step ${index + 1} ('${step.tool}') needs more input: ${error.message}. " +
                        "Call the skill again with those parameters.",
                )
            }
            val args = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(renderedJson) as? JsonObject
            }.getOrNull() ?: JsonObject(emptyMap())
            val result = delegate.execute(args, context)
            lines += when (result) {
                is ToolResult.Success -> {
                    val output = if (delegate.spec.untrustedOutput) {
                        com.verlintas.baic2.core.model.ToolTrust.wrap(result.output)
                    } else {
                        result.output
                    }
                    "✓ ${step.tool}: ${output.take(200)}"
                }
                is ToolResult.Failure -> {
                    lines += "✗ ${step.tool}: ${result.reason}"
                    return ToolResult.Failure(
                        "Skill '${skill.name}' stopped at step ${index + 1}:\n" + lines.joinToString("\n"),
                    )
                }
                is ToolResult.Denied -> return ToolResult.Denied(
                    "Skill '${skill.name}' step ${index + 1} denied: ${result.reason}",
                )
            }
        }
        return ToolResult.Success("Skill '${skill.name}' finished:\n" + lines.joinToString("\n"))
    }

    private companion object {
        const val MAX_NESTING_DEPTH = 3
    }
}

/** Loads an installed skill and registers its composite tools. */
class LoadSkillTool(
    private val repository: SkillRepository,
    private val registry: dagger.Lazy<ToolRegistry>,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "load_skill",
        description = "Load an installed skill by id or name; its tools become available and its " +
            "instructions are returned. Call list-friendly ids from installed skills.",
        parametersJson = """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""",
        readOnly = false,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val id = (arguments["id"] as? JsonPrimitive)?.content?.trim()
            ?: return ToolResult.Failure("Missing 'id' argument")
        val skill = repository.find(id)
            ?: return ToolResult.Failure(
                "No installed skill matches '$id'. Ask the user to import one in Library → Skills.",
            )
        if (skill.tools.isEmpty()) {
            return ToolResult.Failure("Skill '${skill.name}' has no tools to load.")
        }
        val toolRegistry = registry.get()
        val skipped = mutableListOf<String>()
        toolRegistry.registerDynamic(
            skill.tools.filter { definition ->
                // A skill must never shadow a built-in tool name: that would let
                // a recipe hijack an existing capability (and its trust level).
                val existing = toolRegistry.tool(definition.id)
                val shadowingBuiltIn = existing != null && existing !is CompositeSkillTool
                if (shadowingBuiltIn) skipped += definition.id
                !shadowingBuiltIn
            }.map { definition ->
                // Unknown step tools default to HIGH so the gate stays closed.
                val stepTools = definition.steps.mapNotNull { step -> toolRegistry.tool(step.tool) }
                val worst = definition.steps
                    .map { step -> toolRegistry.tool(step.tool)?.spec?.danger ?: DangerLevel.HIGH }
                    .maxByOrNull { it.ordinal }
                    ?: DangerLevel.LOW
                CompositeSkillTool(
                    skill = skill,
                    definition = definition,
                    danger = worst,
                    untrustedOutput = stepTools.any { it.spec.untrustedOutput },
                ) { toolRegistry }
            },
        )
        return ToolResult.Success(
            buildString {
                append("Loaded skill '${skill.name}' v${skill.version}.\n")
                append("Tools now available: ${skill.tools.map { it.id }.joinToString()}\n")
                if (skipped.isNotEmpty()) {
                    append("Refused (name collides with an existing tool): ${skipped.joinToString()}\n")
                }
                if (skill.instructions.isNotBlank()) {
                    append("\nInstructions:\n${skill.instructions.take(2_000)}")
                }
            },
        )
    }
}
