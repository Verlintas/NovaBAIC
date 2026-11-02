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

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: Long = 0L,
    val title: String = "",
    val agentId: Long? = null,
    /** Mode profile id (ModeProfile.id); stock ids match legacy rows. */
    val mode: String = "CHAT",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class ChatMessage(
    val id: Long = 0L,
    val conversationId: Long = 0L,
    val role: ChatRole,
    val content: String = "",
    val thinking: String? = null,
    /** Signature for [thinking] when the provider requires it back (Anthropic). */
    val thinkingSignature: String? = null,
    /** Wall-clock milliseconds spent thinking (for "Thought for Ns" display). */
    val thinkingMs: Long? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val model: String? = null,
    val createdAt: Long = 0L,
    val starred: Boolean = false,
    val usageInput: Long? = null,
    val usageOutput: Long? = null,
)

/** A restorable backup of the messages a compression replaced. */
@Serializable
data class MessageSnapshot(
    val id: Long = 0L,
    val conversationId: Long,
    val carrierId: Long,
    val keepFromMessageId: Long,
    val messages: List<ChatMessage>,
    val createdAt: Long,
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String = "{}",
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.PENDING,
    /** Gemini thought signature that must be echoed with the function call. */
    val signature: String? = null,
)

@Serializable
enum class ToolCallStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    DENIED,
    REJECTED,
}
