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

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String = "{}",
    val readOnly: Boolean = false,
    val danger: DangerLevel = DangerLevel.LOW,
    val parallelSafe: Boolean = false,
    /**
     * Internal capability (memory recall/notes): available in every mode and
     * exempt from the read-only and confirmation gates - it touches nothing
     * outside the agent's own mind.
     */
    val alwaysAvailable: Boolean = false,
    /**
     * True when the tool returns text the user did not write (web pages,
     * notifications, OCR). Such output is data, never instructions; the loop
     * wraps it and marks the run as tainted so high-danger calls are gated.
     */
    val untrustedOutput: Boolean = false,
    /**
     * Deterministic reads (web pages, weather, file listings): the runner
     * answers a repeated identical call from the run's cache instead of
     * paying the network/read cost again.
     */
    val cacheable: Boolean = false,
)

@Serializable
enum class DangerLevel {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
data class ProviderError(
    val kind: Kind,
    val message: String,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Int? = null,
) {
    @Serializable
    enum class Kind {
        NETWORK,
        TIMEOUT,
        AUTH,
        RATE_LIMIT,
        SERVER,
        INVALID_REQUEST,
        UNKNOWN,
    }
}

/** Provider-level streaming events: a thin, vendor-neutral wire model. */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent

    data class ThinkingDelta(val text: String) : StreamEvent

    /** Anthropic extended thinking: opaque signature to echo back verbatim. */
    data class ThinkingSignature(val signature: String) : StreamEvent

    data class ToolCallsDone(val calls: List<ToolCall>) : StreamEvent

    data class Usage(val promptTokens: Long?, val completionTokens: Long?) : StreamEvent

    data object Done : StreamEvent

    data class Failed(val error: ProviderError) : StreamEvent
}

data class ChatRequest(
    val config: ProviderConfig,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
)

/**
 * A model backend. Implementations translate vendor wires into
 * [StreamEvent]; failures are typed, never stringly.
 */
interface ChatProvider {
    fun stream(request: ChatRequest): Flow<StreamEvent>

    suspend fun listModels(config: ProviderConfig): List<String> = emptyList()
}
