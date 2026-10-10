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

package com.verlintas.baic2.core.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int?,
    val reasoning: Boolean,
    val systemPrompt: String,
    val encryptedApiKey: String,
    val isDefault: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val agentId: Long?,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"]), Index(value = ["starred"]), Index(value = ["createdAt"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val role: String,
    val content: String,
    val thinking: String?,
    val thinkingSignature: String? = null,
    val thinkingMs: Long? = null,
    val toolCallsJson: String,
    val attachmentsJson: String = "[]",
    val toolCallId: String?,
    val toolName: String?,
    val model: String?,
    val createdAt: Long,
    val starred: Boolean = false,
    val usageInput: Long? = null,
    val usageOutput: Long? = null,
)

@Entity(
    tableName = "notes",
    indices = [Index(value = ["kind"]), Index(value = ["updatedAt"])],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val kind: String,
    val content: String,
    val importance: Int,
    val pinned: Boolean,
    val conversationId: Long?,
    val messageId: Long?,
    val whenAt: Long?,
    /** Perishable facts (location, "currently…") expire; null = durable. */
    val expiresAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int,
    val strength: Double,
    val source: String,
    val entities: String,
    val suppressed: Boolean,
    val supersededBy: Long?,
    val archived: Boolean,
)

/** A Hebbian association: notes recalled together wire together. */
@Entity(
    tableName = "note_links",
    primaryKeys = ["a", "b"],
    indices = [Index(value = ["b"])],
)
data class NoteLinkEntity(
    val a: Long,
    val b: Long,
    val weight: Float,
    val updatedAt: Long,
)

@Entity(tableName = "core_memory")
data class CoreMemoryEntity(
    @PrimaryKey val slot: String,
    val content: String,
    val updatedAt: Long,
) {
    companion object {
        const val SLOT_USER = "user"
        const val SLOT_CONTEXT = "context"
    }
}

/** Old versions of a note, kept when it is rewritten: notes never lose their past. */
@Entity(
    tableName = "note_revisions",
    indices = [Index(value = ["noteId"])],
)
data class NoteRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val content: String,
    val importance: Int,
    val entities: String,
    val replacedAt: Long,
)

/**
 * "Do not record this" directives: a promise made in conversation, stored as
 * executable state so the curator and memory_write must respect it.
 */
@Entity(tableName = "memory_holds")
data class MemoryHoldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val content: String,
    val reason: String?,
    val createdAt: Long,
)

/** Consolidation log: "empty plan" and "run failed" must be distinguishable. */
@Entity(
    tableName = "curator_runs",
    indices = [Index(value = ["ranAt"])],
)
data class CuratorRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ranAt: Long,
    val trigger: String,
    val conversationId: Long?,
    val messages: Int,
    val windowFrom: Long,
    val windowTo: Long,
    val notesScanned: Int,
    val added: Int,
    val revised: Int,
    val forgotten: Int,
    val rehearsed: Int,
    val parsed: Boolean,
    val error: String?,
)

@Entity(
    tableName = "runs",
    indices = [Index(value = ["conversationId"])],
)
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val mode: String,
    val state: String,
    val startedAt: Long,
    val updatedAt: Long,
    val roundsUsed: Int = 0,
    val toolCallsUsed: Int = 0,
)

data class ConversationSummary(
    @Embedded val conversation: ConversationEntity,
    val lastMessage: String?,
)

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val prompt: String,
    val mode: String,
    val agentId: Long?,
    val timeOfDay: String,
    val daysOfWeekJson: String,
    val enabled: Boolean,
    val conversationId: Long?,
    val lastRunAt: Long,
    val nextRunAt: Long,
    val lastResult: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "message_snapshots",
    indices = [Index(value = ["conversationId"])],
)
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val carrierId: Long,
    val keepFromMessageId: Long,
    val payloadJson: String,
    val createdAt: Long,
)

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val trigger: String,
    val timeOfDay: String?,
    val daysOfWeekJson: String,
    val batteryBelow: Int?,
    val actionsJson: String,
    val enabled: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val url: String,
    val headersJson: String,
    val enabled: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val conversationId: Long,
    val stepsJson: String,
    val updatedAt: Long,
)
