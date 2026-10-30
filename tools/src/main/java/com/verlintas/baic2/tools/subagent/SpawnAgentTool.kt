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

package com.verlintas.baic2.tools.subagent

import com.verlintas.baic2.core.engine.AgentEvent
import com.verlintas.baic2.core.engine.AgentLoop
import com.verlintas.baic2.core.engine.ConfirmationGate
import com.verlintas.baic2.core.engine.ToolCatalog
import com.verlintas.baic2.core.engine.ToolRunContext
import com.verlintas.baic2.core.engine.ToolRunner
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatProvider
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ProviderId
import com.verlintas.baic2.core.model.RunBudget
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.core.model.ToolTrust
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import javax.inject.Inject
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Runs a scoped child agent with its own budget and context window, then
 * returns a compact report. Sub-agents cannot spawn further sub-agents, which
 * keeps depth at one and budgets predictable.
 *
 * Taint crosses the boundary in both directions: a tainted parent spawns a
 * tainted child (which then confirms/denies HIGH calls like its parent), and a
 * child that touched untrusted content returns a marked report.
 */
class SpawnAgentTool @Inject constructor(
    private val providerFactory: (ProviderId) -> ChatProvider,
    private val toolCatalog: dagger.Lazy<ToolCatalog>,
    private val toolRunner: dagger.Lazy<ToolRunner>,
    private val confirmationGate: ConfirmationGate,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "spawn_agent",
        description = "Delegate a self-contained task to a sub-agent with its own context and budget. " +
            "mode=research (read-only tools, default) or mode=max (full tools). Returns the sub-agent's report. " +
            "Use several spawn_agent calls in one round for parallel work.",
        parametersJson = """{"type":"object","properties":{"task":{"type":"string"},"mode":{"type":"string","enum":["research","max"]}},"required":["task"]}""",
        readOnly = false,
        danger = DangerLevel.MEDIUM,
        parallelSafe = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val task = (arguments["task"] as? JsonPrimitive)?.content?.trim()
            ?: return ToolResult.Failure("Missing 'task' argument")
        val mode = when ((arguments["mode"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
            "max" -> AppMode.MAX
            else -> AppMode.CHAT_PLUS
        }
        return runChild(task, mode, context.run)
    }

    internal suspend fun runChild(task: String, mode: AppMode, run: ToolRunContext): ToolResult {
        val config = run.config
            ?: return ToolResult.Failure("Sub-agents need an active provider configuration.")
        // A child can never be more privileged than its parent: an ACT
        // confirmation must not become a blank cheque for MAX in the child.
        val effectiveMode = when (run.mode) {
            AppMode.MAX -> mode
            AppMode.ACT -> if (mode.ordinal > AppMode.CHAT_PLUS.ordinal) AppMode.CHAT_PLUS else mode
            else -> if (mode.ordinal > run.mode.ordinal) run.mode else mode
        }
        val budget = if (effectiveMode == AppMode.CHAT_PLUS) {
            RunBudget(maxRounds = 8, maxToolCalls = 20, maxWallClockMs = 300_000)
        } else {
            RunBudget(maxRounds = 16, maxToolCalls = 40, maxWallClockMs = 600_000)
        }

        val excluded = setOf("spawn_agent")
        val parentCatalog = toolCatalog.get()
        val childCatalog = object : ToolCatalog {
            override fun specs(mode: AppMode) =
                parentCatalog.specs(mode).filter { it.name !in excluded }

            override fun find(name: String) =
                if (name in excluded) null else parentCatalog.find(name)
        }

        val loop = AgentLoop(
            providerFactory = providerFactory,
            toolCatalog = childCatalog,
            toolRunner = toolRunner.get(),
            confirmationGate = ConfirmationGate { false },
        )

        val events = try {
            loop.run(
                config = config,
                mode = effectiveMode,
                history = listOf(ChatMessage(role = ChatRole.USER, content = task)),
                conversationId = run.conversationId,
                initialTaint = run.taint.tainted,
                unattended = run.unattended,
                budgetOverride = budget,
                triggerMessageId = run.triggerMessageId,
                primedNoteIds = run.primedNoteIds,
            ).toList()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ToolResult.Failure("Sub-agent crashed: ${e.message}")
        }

        val failure = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
        if (failure != null) {
            return ToolResult.Failure("Sub-agent failed (${failure.error.kind}): ${failure.error.message}")
        }
        val report = events.filterIsInstance<AgentEvent.AssistantMessage>()
            .lastOrNull { it.message.toolCalls.isEmpty() }
            ?.message?.content
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("Sub-agent finished without an answer.")
        val toolNames = events.filterIsInstance<AgentEvent.ToolCallFinished>()
            .map { it.call.name }
            .distinct()
        val rounds = events.filterIsInstance<AgentEvent.RoundStarted>().size
        // A child that read untrusted content returns a marked report, so the
        // parent's run inherits the taint too.
        val childTainted = events.filterIsInstance<AgentEvent.ToolCallFinished>().any { it.untrusted }
        val reportText = if (childTainted) ToolTrust.wrap(report.take(3_000)) else report.take(3_000)

        return ToolResult.Success(
            buildString {
                append("Sub-agent report (${if (mode == AppMode.CHAT_PLUS) "research" else "max"}, ")
                append("$rounds round(s)")
                if (toolNames.isNotEmpty()) append(", tools: ${toolNames.joinToString()}")
                append("):\n")
                append(reportText)
            },
        )
    }
}
