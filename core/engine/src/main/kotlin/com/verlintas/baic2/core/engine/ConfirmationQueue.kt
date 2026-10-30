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

import com.verlintas.baic2.core.model.ToolCall
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout

/** A confirmation request plus the chat that must answer it. */
data class ConfirmationRequest(
    val call: ToolCall,
    val conversationId: Long?,
)

/**
 * Bridge between the suspended agent loop and the confirmation UI: the loop
 * awaits [confirm] while the UI answers through [respond]. Unanswered requests
 * time out to "deny" so a run can never hang forever.
 */
class ConfirmationQueue(
    private val timeoutMs: Long = 300_000,
) : ConfirmationGate {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _requests = MutableSharedFlow<ConfirmationRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<ConfirmationRequest> = _requests

    override suspend fun confirm(call: ToolCall): Boolean = confirm(call, null)

    override suspend fun confirm(call: ToolCall, conversationId: Long?): Boolean {
        // Nobody is listening (background/unattended run): deny immediately
        // instead of buffering the request until the 5-minute timeout.
        if (_requests.subscriptionCount.value == 0) return false
        val deferred = CompletableDeferred<Boolean>()
        if (pending.putIfAbsent(call.id, deferred) != null) {
            return false
        }
        if (!_requests.tryEmit(ConfirmationRequest(call, conversationId))) {
            pending.remove(call.id)
            return false
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            false
        } finally {
            pending.remove(call.id)
        }
    }

    fun respond(callId: String, allow: Boolean): Boolean =
        pending.remove(callId)?.complete(allow) ?: false
}
