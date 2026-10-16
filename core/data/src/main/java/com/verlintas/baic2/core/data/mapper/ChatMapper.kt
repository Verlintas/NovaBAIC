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

package com.verlintas.baic2.core.data.mapper

import com.verlintas.baic2.core.data.db.AgentEntity
import com.verlintas.baic2.core.data.db.AutomationEntity
import com.verlintas.baic2.core.data.db.ConversationEntity
import com.verlintas.baic2.core.data.db.CoreMemoryEntity
import com.verlintas.baic2.core.data.db.McpServerEntity
import com.verlintas.baic2.core.data.db.MessageEntity
import com.verlintas.baic2.core.data.db.NoteEntity
import com.verlintas.baic2.core.data.db.ScheduledTaskEntity
import com.verlintas.baic2.core.data.db.SnapshotEntity
import com.verlintas.baic2.core.data.db.PlanEntity
import com.verlintas.baic2.core.data.db.RunEntity
import com.verlintas.baic2.core.data.db.RunSummaryRow
import com.verlintas.baic2.core.data.db.MessageSearchRow
import com.verlintas.baic2.core.model.Agent
import com.verlintas.baic2.core.model.Automation
import com.verlintas.baic2.core.model.AutomationAction
import com.verlintas.baic2.core.model.AutomationTrigger
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.Conversation
import com.verlintas.baic2.core.model.CoreMemory
import com.verlintas.baic2.core.model.McpServer
import com.verlintas.baic2.core.model.MessageHit
import com.verlintas.baic2.core.model.MemoryText
import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind
import com.verlintas.baic2.core.model.NoteSource
import com.verlintas.baic2.core.model.MessageSnapshot
import com.verlintas.baic2.core.model.ScheduledTask
import com.verlintas.baic2.core.model.Plan
import com.verlintas.baic2.core.model.PlanStep
import com.verlintas.baic2.core.model.ProviderId
import com.verlintas.baic2.core.model.Run
import com.verlintas.baic2.core.model.RunState
import com.verlintas.baic2.core.model.RunSummary
import com.verlintas.baic2.core.model.ToolCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ChatMapper @Inject constructor(private val json: Json) {

    fun agentToModel(entity: AgentEntity): Agent = Agent(
        id = entity.id,
        name = entity.name,
        provider = enumOf(entity.provider, ProviderId.OPENAI_COMPATIBLE),
        baseUrl = entity.baseUrl,
        model = entity.model,
        temperature = entity.temperature,
        maxTokens = entity.maxTokens,
        reasoning = entity.reasoning,
        systemPrompt = entity.systemPrompt,
        isDefault = entity.isDefault,
    )

    fun agentToEntity(agent: Agent, encryptedApiKey: String, createdAt: Long): AgentEntity = AgentEntity(
        id = agent.id,
        name = agent.name,
        provider = agent.provider.name,
        baseUrl = agent.baseUrl,
        model = agent.model,
        temperature = agent.temperature,
        maxTokens = agent.maxTokens,
        reasoning = agent.reasoning,
        systemPrompt = agent.systemPrompt,
        encryptedApiKey = encryptedApiKey,
        isDefault = agent.isDefault,
        createdAt = createdAt,
    )

    fun conversationToModel(entity: ConversationEntity): Conversation = Conversation(
        id = entity.id,
        title = entity.title,
        agentId = entity.agentId,
        mode = enumOf(entity.mode, AppMode.CHAT),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    fun conversationToEntity(conversation: Conversation): ConversationEntity = ConversationEntity(
        id = conversation.id,
        title = conversation.title,
        agentId = conversation.agentId,
        mode = conversation.mode.name,
        createdAt = conversation.createdAt,
        updatedAt = conversation.updatedAt,
    )

    fun messageToModel(entity: MessageEntity): ChatMessage = ChatMessage(
        id = entity.id,
        conversationId = entity.conversationId,
        role = enumOf(entity.role, ChatRole.USER),
        content = entity.content,
        thinking = entity.thinking,
        thinkingSignature = entity.thinkingSignature,
        thinkingMs = entity.thinkingMs,
        toolCalls = decodeToolCalls(entity.toolCallsJson),
        attachments = decodeAttachments(entity.attachmentsJson),
        toolCallId = entity.toolCallId,
        toolName = entity.toolName,
        model = entity.model,
        createdAt = entity.createdAt,
        starred = entity.starred,
        usageInput = entity.usageInput,
        usageOutput = entity.usageOutput,
    )

    fun messageToEntity(message: ChatMessage): MessageEntity = MessageEntity(
        id = message.id,
        conversationId = message.conversationId,
        role = message.role.name,
        content = message.content,
        thinking = message.thinking,
        thinkingSignature = message.thinkingSignature,
        thinkingMs = message.thinkingMs,
        toolCallsJson = encodeToolCalls(message.toolCalls),
        attachmentsJson = encodeAttachments(message.attachments),
        toolCallId = message.toolCallId,
        toolName = message.toolName,
        model = message.model,
        createdAt = message.createdAt,
        starred = message.starred,
        usageInput = message.usageInput,
        usageOutput = message.usageOutput,
    )

    /** Ephemeral base64 payloads are never persisted. */
    fun encodeAttachments(attachments: List<com.verlintas.baic2.core.model.Attachment>): String =
        json.encodeToString(attachments.map { it.copy(base64 = null) })

    private fun decodeAttachments(raw: String): List<com.verlintas.baic2.core.model.Attachment> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<com.verlintas.baic2.core.model.Attachment>>(raw)
        }.getOrDefault(emptyList())
    }

    fun noteToModel(entity: NoteEntity): Note = Note(
        id = entity.id,
        kind = enumOf(entity.kind, NoteKind.FACT),
        content = entity.content,
        importance = entity.importance,
        pinned = entity.pinned,
        conversationId = entity.conversationId,
        messageId = entity.messageId,
        whenAt = entity.whenAt,
        expiresAt = entity.expiresAt,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        lastAccessedAt = entity.lastAccessedAt,
        accessCount = entity.accessCount,
        strength = entity.strength,
        source = enumOf(entity.source, NoteSource.USER),
        entities = MemoryText.decodeEntities(entity.entities),
        suppressed = entity.suppressed,
        supersededBy = entity.supersededBy,
        archived = entity.archived,
    )

    fun coreToModel(slots: List<CoreMemoryEntity>): CoreMemory = CoreMemory(
        user = slots.firstOrNull { it.slot == CoreMemoryEntity.SLOT_USER }?.content.orEmpty(),
        context = slots.firstOrNull { it.slot == CoreMemoryEntity.SLOT_CONTEXT }?.content.orEmpty(),
        updatedAt = slots.maxOfOrNull { it.updatedAt } ?: 0L,
    )

    fun messageHitToModel(row: MessageSearchRow): MessageHit = MessageHit(
        messageId = row.id,
        conversationId = row.conversationId,
        conversationTitle = row.conversationTitle,
        role = enumOf(row.role, ChatRole.USER),
        content = row.content,
        createdAt = row.createdAt,
    )

    fun encodeToolCalls(toolCalls: List<ToolCall>): String = json.encodeToString(toolCalls)

    fun encodeMessages(messages: List<com.verlintas.baic2.core.model.ChatMessage>): String =
        json.encodeToString(messages)

    fun scheduledTaskToModel(entity: ScheduledTaskEntity): ScheduledTask = ScheduledTask(
        id = entity.id,
        name = entity.name,
        prompt = entity.prompt,
        mode = enumOf(entity.mode, AppMode.MAX),
        agentId = entity.agentId,
        timeOfDay = entity.timeOfDay,
        daysOfWeek = entity.daysOfWeekJson
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet(),
        enabled = entity.enabled,
        conversationId = entity.conversationId,
        lastRunAt = entity.lastRunAt,
        nextRunAt = entity.nextRunAt,
        lastResult = entity.lastResult,
        createdAt = entity.createdAt,
    )

    fun scheduledTaskToEntity(task: ScheduledTask): ScheduledTaskEntity = ScheduledTaskEntity(
        id = task.id,
        name = task.name,
        prompt = task.prompt,
        mode = task.mode.name,
        agentId = task.agentId,
        timeOfDay = task.timeOfDay,
        daysOfWeekJson = task.daysOfWeek.sorted().joinToString(","),
        enabled = task.enabled,
        conversationId = task.conversationId,
        lastRunAt = task.lastRunAt,
        nextRunAt = task.nextRunAt,
        lastResult = task.lastResult,
        createdAt = task.createdAt,
    )

    fun snapshotToModel(entity: SnapshotEntity): MessageSnapshot = MessageSnapshot(
        id = entity.id,
        conversationId = entity.conversationId,
        carrierId = entity.carrierId,
        keepFromMessageId = entity.keepFromMessageId,
        messages = runCatching {
            json.decodeFromString<List<com.verlintas.baic2.core.model.ChatMessage>>(entity.payloadJson)
        }.getOrDefault(emptyList()),
        createdAt = entity.createdAt,
    )

    fun runToModel(entity: RunEntity): Run = Run(
        id = entity.id,
        conversationId = entity.conversationId,
        mode = enumOf(entity.mode, AppMode.CHAT),
        state = enumOf(entity.state, RunState.RUNNING),
        startedAt = entity.startedAt,
        updatedAt = entity.updatedAt,
        roundsUsed = entity.roundsUsed,
        toolCallsUsed = entity.toolCallsUsed,
    )

    fun runSummaryToModel(row: RunSummaryRow): RunSummary = RunSummary(
        id = row.id,
        conversationId = row.conversationId,
        conversationTitle = row.conversationTitle,
        mode = enumOf(row.mode, AppMode.CHAT),
        state = enumOf(row.state, RunState.RUNNING),
        startedAt = row.startedAt,
        updatedAt = row.updatedAt,
    )

    fun mcpServerToModel(entity: McpServerEntity): McpServer = McpServer(
        id = entity.id,
        name = entity.name,
        url = entity.url,
        headers = runCatching {
            json.decodeFromString<Map<String, String>>(entity.headersJson)
        }.getOrDefault(emptyMap()),
        enabled = entity.enabled,
        createdAt = entity.createdAt,
    )

    fun mcpServerToEntity(server: McpServer): McpServerEntity = McpServerEntity(
        id = server.id,
        name = server.name,
        url = server.url,
        headersJson = json.encodeToString(server.headers),
        enabled = server.enabled,
        createdAt = server.createdAt,
    )

    fun automationToModel(entity: AutomationEntity): Automation = Automation(
        id = entity.id,
        name = entity.name,
        trigger = enumOf(entity.trigger, AutomationTrigger.TIME),
        timeOfDay = entity.timeOfDay,
        daysOfWeek = runCatching {
            json.decodeFromString<List<Int>>(entity.daysOfWeekJson)
        }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() },
        batteryBelow = entity.batteryBelow,
        notificationPackages = runCatching {
            json.decodeFromString<List<String>>(entity.notificationPackagesJson)
        }.getOrDefault(emptyList()),
        notificationKeyword = entity.notificationKeyword,
        actions = runCatching {
            json.decodeFromString<List<AutomationAction>>(entity.actionsJson)
        }.getOrDefault(emptyList()),
        enabled = entity.enabled,
        createdAt = entity.createdAt,
    )

    fun automationToEntity(automation: Automation): AutomationEntity = AutomationEntity(
        id = automation.id,
        name = automation.name,
        trigger = automation.trigger.name,
        timeOfDay = automation.timeOfDay,
        daysOfWeekJson = json.encodeToString(automation.daysOfWeek ?: emptyList<Int>()),
        batteryBelow = automation.batteryBelow,
        notificationPackagesJson = json.encodeToString(automation.notificationPackages),
        notificationKeyword = automation.notificationKeyword,
        actionsJson = json.encodeToString(automation.actions),
        enabled = automation.enabled,
        createdAt = automation.createdAt,
    )

    fun planToModel(entity: PlanEntity): Plan = Plan(
        conversationId = entity.conversationId,
        steps = runCatching {
            json.decodeFromString<List<PlanStep>>(entity.stepsJson)
        }.getOrDefault(emptyList()),
        updatedAt = entity.updatedAt,
    )

    private fun decodeToolCalls(raw: String): List<ToolCall> {
        if (raw.isBlank()) return emptyList()
        // Corrupted history must never crash the app; an empty list keeps the
        // conversation readable and re-sendable.
        return runCatching { json.decodeFromString<List<ToolCall>>(raw) }.getOrDefault(emptyList())
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
