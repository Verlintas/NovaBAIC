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

package com.verlintas.baic2.tools.scheduling

import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.repository.PlanRepository
import com.verlintas.baic2.core.data.repository.RunRepository
import com.verlintas.baic2.core.engine.AgentEvent
import com.verlintas.baic2.core.engine.AgentLoop
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.RunState
import com.verlintas.baic2.core.model.ScheduledTask
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolCallStatus
import com.verlintas.baic2.core.model.ToolTrust
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a scheduled prompt through the full agent loop without any UI:
 * persists the user message, assistant messages, tool results and the run
 * record exactly like an interactive turn, so the conversation and Tasks
 * center stay coherent.
 */
@Singleton
class ScheduledTaskRunner @Inject constructor(
    private val agentRepository: AgentRepository,
    private val conversationRepository: ConversationRepository,
    private val runRepository: RunRepository,
    private val planRepository: PlanRepository,
    private val agentLoop: AgentLoop,
) {

    data class Outcome(
        val conversationId: Long,
        val success: Boolean,
        val summary: String,
    )

    suspend fun run(task: ScheduledTask): Outcome {
        val config = runCatching { agentRepository.resolveConfig(task.agentId) }.getOrNull()
            ?: return Outcome(task.conversationId ?: 0L, false, "未配置可用的 AI 服务")
        val agent = task.agentId?.let { agentRepository.getAgent(it) } ?: agentRepository.getDefaultAgent()
        val mode = if (task.mode == AppMode.ACT) AppMode.MAX else task.mode

        val conversationId = task.conversationId ?: conversationRepository.create(
            agentId = task.agentId,
            mode = mode,
            title = "定时 · ${task.name}".take(24),
        )
        val triggerMessageId = conversationRepository.append(
            ChatMessage(
                conversationId = conversationId,
                role = ChatRole.USER,
                content = task.prompt,
                createdAt = System.currentTimeMillis(),
            ),
        )
        if (conversationRepository.get(conversationId)?.title.isNullOrBlank()) {
            conversationRepository.updateTitle(conversationId, task.prompt.take(24))
        }

        val runId = runRepository.start(conversationId, mode)
        var rounds = 0
        var toolCalls = 0
        var assistantMessageId: Long? = null
        var pendingCalls: List<ToolCall> = emptyList()
        val text = StringBuilder()
        var failure: String? = null

        try {
            agentLoop.run(
                config = config,
                mode = mode,
                customSystemPrompt = agent?.systemPrompt.orEmpty(),
                history = conversationRepository.getMessages(conversationId),
                conversationId = conversationId,
                planContext = planRepository.getPlan(conversationId)?.render(),
                triggerMessageId = triggerMessageId,
                unattended = true,
            ).collect { event ->
                when (event) {
                    is AgentEvent.RoundStarted -> {
                        rounds = maxOf(rounds, event.round)
                        pendingCalls = emptyList()
                    }

                    is AgentEvent.TextDelta -> text.append(event.text)

                    is AgentEvent.ThinkingDelta -> Unit

                    is AgentEvent.AssistantMessage -> {
                        assistantMessageId = conversationRepository.append(
                            event.message.copy(
                                conversationId = conversationId,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        pendingCalls = event.message.toolCalls
                    }

                    is AgentEvent.ToolCallStarted -> {
                        pendingCalls = pendingCalls.map { call ->
                            if (call.id == event.call.id) {
                                event.call.copy(status = ToolCallStatus.RUNNING)
                            } else {
                                call
                            }
                        }
                        assistantMessageId?.let { conversationRepository.updateToolCalls(it, pendingCalls) }
                    }

                    is AgentEvent.ToolCallFinished -> {
                        // Budget ledger parity with the engine: only calls that
                        // actually ran (done/failed) are counted.
                        if (event.call.status == ToolCallStatus.DONE ||
                            event.call.status == ToolCallStatus.FAILED
                        ) {
                            toolCalls++
                        }
                        pendingCalls = pendingCalls.map { call ->
                            if (call.id == event.call.id) event.call else call
                        }
                        assistantMessageId?.let { conversationRepository.updateToolCalls(it, pendingCalls) }
                        conversationRepository.append(
                            ChatMessage(
                                conversationId = conversationId,
                                role = ChatRole.TOOL,
                                content = if (event.untrusted) {
                                    ToolTrust.wrap(event.call.result.orEmpty())
                                } else {
                                    event.call.result.orEmpty()
                                },
                                toolCallId = event.call.id,
                                toolName = event.call.name,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }

                    is AgentEvent.Failed -> failure = event.error.message
                    is AgentEvent.Usage, AgentEvent.Completed -> Unit
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runRepository.finish(runId, RunState.CANCELLED, rounds, toolCalls)
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        }

        val state = if (failure == null) RunState.COMPLETED else RunState.FAILED
        runRepository.finish(runId, state, rounds, toolCalls)
        val summary = (failure ?: text.toString())
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { if (failure == null) "已完成" else "运行失败" }
            .take(140)
        return Outcome(conversationId, failure == null, summary)
    }
}
