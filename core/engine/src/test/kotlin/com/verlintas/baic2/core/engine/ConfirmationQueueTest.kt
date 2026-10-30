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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class ConfirmationQueueTest {

    private val call = ToolCall(id = "c1", name = "open_app", argumentsJson = "{}")

    @Test
    fun confirmWaitsForTheUserResponse() = runTest {
        val queue = ConfirmationQueue(timeoutMs = 10_000)
        val result = async { queue.confirm(call) }
        val request = queue.requests.first()
        assertEquals("c1", request.call.id)
        assertEquals(null, request.conversationId)
        assertTrue(queue.respond("c1", allow = true))
        assertTrue(result.await())
    }

    @Test
    fun requestCarriesItsConversation() = runTest {
        val queue = ConfirmationQueue(timeoutMs = 10_000)
        val result = async { queue.confirm(call, conversationId = 7L) }
        val request = queue.requests.first()
        assertEquals(7L, request.conversationId)
        assertTrue(queue.respond("c1", allow = true))
        assertTrue(result.await())
    }

    @Test
    fun denialIsReported() = runTest {
        val queue = ConfirmationQueue(timeoutMs = 10_000)
        val result = async { queue.confirm(call) }
        queue.requests.first()
        queue.respond("c1", allow = false)
        assertFalse(result.await())
    }

    @Test
    fun timeoutDenies() = runTest {
        val queue = ConfirmationQueue(timeoutMs = 50)
        assertFalse(queue.confirm(call))
    }

    @Test
    fun unknownResponseIsIgnored() = runTest {
        val queue = ConfirmationQueue(timeoutMs = 10_000)
        assertFalse(queue.respond("nope", allow = true))
    }
}
