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

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** A mid-run question from the agent to the person. */
data class UserQuestion(
    val id: String,
    val question: String,
    val options: List<String> = emptyList(),
    /** Only the chat showing this conversation may surface the dialog. */
    val conversationId: Long? = null,
)

/**
 * Bridge between the suspended agent loop and the chat UI: the loop awaits
 * [ask] while the person answers through [respond]. Unanswered questions
 * return null (nobody watching, dismissed, or timed out) so a run never
 * hangs on a question.
 */
class QuestionQueue(
    private val timeoutMs: Long = 180_000,
) {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    private val _requests = MutableSharedFlow<UserQuestion>(extraBufferCapacity = 4)
    val requests: SharedFlow<UserQuestion> = _requests

    // Questions are answered one at a time: two parallel children asking must
    // not overwrite each other's dialog slot.
    private val serial = kotlinx.coroutines.sync.Mutex()

    suspend fun ask(question: UserQuestion): String? = serial.withLock {
        // Background/unattended: do not wait at all.
        if (_requests.subscriptionCount.value == 0) return@withLock null
        val deferred = CompletableDeferred<String?>()
        if (pending.putIfAbsent(question.id, deferred) != null) return@withLock null
        if (!_requests.tryEmit(question)) {
            pending.remove(question.id)
            return@withLock null
        }
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            pending.remove(question.id)
        }
    }

    fun respond(id: String, answer: String?): Boolean = pending.remove(id)?.complete(answer) ?: false
}
