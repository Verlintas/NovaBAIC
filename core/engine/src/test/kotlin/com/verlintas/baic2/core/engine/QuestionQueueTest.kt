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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class QuestionQueueTest {

    private val question = UserQuestion(
        id = "q1",
        question = "Which account should I use?",
        options = listOf("Personal", "Work"),
    )

    @Test
    fun askWaitsForTheAnswer() = runTest {
        val queue = QuestionQueue(timeoutMs = 10_000)
        val result = async { queue.ask(question) }
        val request = queue.requests.first()
        assertEquals("q1", request.id)
        assertEquals(listOf("Personal", "Work"), request.options)
        assertTrue(queue.respond("q1", "Work"))
        assertEquals("Work", result.await())
    }

    @Test
    fun dismissalDeliversNull() = runTest {
        val queue = QuestionQueue(timeoutMs = 10_000)
        val result = async { queue.ask(question) }
        queue.requests.first()
        queue.respond("q1", null)
        assertNull(result.await())
    }

    @Test
    fun timeoutReturnsNull() = runTest {
        val queue = QuestionQueue(timeoutMs = 1_000)
        val result = async { queue.ask(question) }
        queue.requests.first()
        assertNull(result.await())
    }

    @Test
    fun noSubscriberMeansNobodyIsThere() = runTest {
        val queue = QuestionQueue(timeoutMs = 10_000)
        assertNull(queue.ask(question))
    }

    @Test
    fun unknownResponseIsIgnored() = runTest {
        val queue = QuestionQueue(timeoutMs = 10_000)
        assertTrue(!queue.respond("nope", "x"))
    }
}
