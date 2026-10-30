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

package com.verlintas.baic2.core.data.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRunGateTest {

    @Test
    fun onlyOneConversationMayHoldTheAgentSlot() {
        val gate = AgentRunGate()

        assertTrue(gate.tryAcquire(1L))
        assertFalse(gate.tryAcquire(2L))
        assertFalse(gate.tryAcquire(1L))

        gate.release(1L)
        assertTrue(gate.tryAcquire(2L))
    }

    @Test
    fun releaseByANonOwnerDoesNotStealTheSlot() {
        val gate = AgentRunGate()
        assertTrue(gate.tryAcquire(1L))

        gate.release(2L)

        assertFalse(gate.tryAcquire(2L))
    }
}
