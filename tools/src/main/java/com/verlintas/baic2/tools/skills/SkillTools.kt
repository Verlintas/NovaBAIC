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

/** Skill recipes may not orchestrate other agents/automations. */
internal object SkillPolicy {
    val BLOCKED_STEPS = setOf("spawn_agent", "automation")
}

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

    internal val skillId: String get() = skill.id

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
        var sawUntrusted = false
        for ((index, step) in definition.steps.withIndex()) {
            if (step.tool == definition.id) {
                return ToolResult.Failure("Skill step ${index + 1} would recurse into itself; fix the recipe.")
            }
            val delegate = registryRef.tool(step.tool)
                ?: return ToolResult.Failure(
                    "Step ${index + 1} references unknown tool '${step.tool}'. " +
                        "Available: ${registryRef.toolNames.joinToString()}",
                )
            // Skills execute delegates directly, so the loop's unattended gate
            // never sees them: enforce the same policy here.
            if (context.run.unattended &&
                (delegate.spec.danger == DangerLevel.HIGH ||
                    step.tool in com.verlintas.baic2.core.model.ToolTrust.UNATTENDED_BLOCKED ||
                    step.tool in SkillPolicy.BLOCKED_STEPS)
            ) {
                return ToolResult.Denied(
                    "Skill '${skill.name}' step ${index + 1} ('${step.tool}') is not allowed " +
                        "in an unattended run.",
                )
            }
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
            if (delegate.spec.untrustedOutput) {
                // Mark the run immediately, even if this step later fails:
                // its text is already in the step log.
                sawUntrusted = true
                context.run.taint.tainted = true
            }
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
                    val text = "Skill '${skill.name}' stopped at step ${index + 1}:\n" +
                        lines.joinToString("\n")
                    return ToolResult.Failure(
                        if (sawUntrusted) {
                            com.verlintas.baic2.core.model.ToolTrust.wrap(text)
                        } else {
                            text
                        },
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
        // Drop this skill's previous composites so removed tools disappear.
        toolRegistry.toolNames
            .filter { name ->
                (toolRegistry.tool(name) as? CompositeSkillTool)?.skillId == skill.id
            }
            .forEach { name -> toolRegistry.unregisterDynamic(listOf(name)) }
        val skipped = mutableListOf<String>()
        toolRegistry.registerDynamic(
            skill.tools.filter { definition ->
                if (definition.steps.any { it.tool in SkillPolicy.BLOCKED_STEPS }) {
                    skipped += definition.id
                    return@filter false
                }
                // A skill must never shadow a built-in or another skill's tool.
                val existing = toolRegistry.tool(definition.id)
                val collides = existing != null &&
                    (existing !is CompositeSkillTool || existing.skillId != skill.id)
                if (collides) skipped += definition.id
                !collides
            }.map { definition ->
                // Unknown step tools default to HIGH so the gate stays closed;
                // steps in the unattended deny list force HIGH as well.
                val stepTools = definition.steps.mapNotNull { step -> toolRegistry.tool(step.tool) }
                val worst = definition.steps
                    .map { step ->
                        val spec = toolRegistry.tool(step.tool)?.spec
                        when {
                            spec == null -> DangerLevel.HIGH
                            step.tool in com.verlintas.baic2.core.model.ToolTrust.UNATTENDED_BLOCKED ->
                                DangerLevel.HIGH
                            else -> spec.danger
                        }
                    }
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
                    append(
                        "Refused (name collision, or spawn_agent/automation steps): " +
                            skipped.joinToString() + "\n",
                    )
                }
                if (skill.instructions.isNotBlank()) {
                    append("\nInstructions:\n${skill.instructions.take(2_000)}")
                }
            },
        )
    }
}
