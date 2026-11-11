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

package com.verlintas.baic2.core.engine

import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolImage
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent

    data class ThinkingDelta(val text: String) : AgentEvent

    data class RoundStarted(val round: Int) : AgentEvent

    data class AssistantMessage(val message: ChatMessage) : AgentEvent

    data class ToolCallStarted(val call: ToolCall) : AgentEvent

    data class ToolCallFinished(
        val call: ToolCall,
        val untrusted: Boolean = false,
        /** Ephemeral images for the UI receipt; not part of persisted tool text. */
        val images: List<ToolImage> = emptyList(),
    ) : AgentEvent

    data class Usage(val promptTokens: Long?, val completionTokens: Long?) : AgentEvent

    data object Completed : AgentEvent

    data class Failed(val error: AgentFailure) : AgentEvent
}

data class AgentFailure(
    val kind: Kind,
    val message: String,
) {
    enum class Kind {
        PROVIDER,
        BUDGET,
        UNSUPPORTED_PROVIDER,
        INTERNAL,
    }
}
