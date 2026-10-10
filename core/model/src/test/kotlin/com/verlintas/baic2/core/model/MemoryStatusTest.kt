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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStatusTest {

    private val now = 1_700_000_000_000L

    @Test
    fun freshMemoryIsHonestAboutBeingEmpty() {
        val line = MemoryPrompt.statusLine(MemoryStatus(), now)
        assertTrue(line.contains("no notes yet"))
        assertTrue(line.contains("not consolidated yet"))
    }

    @Test
    fun statusReportsCountsConsolidationAndTimeGap() {
        val line = MemoryPrompt.statusLine(
            MemoryStatus(
                activeNotes = 12,
                pinned = 2,
                expiringSoon = 1,
                expired = 1,
                holds = 2,
                lastConsolidatedAt = now - 6 * 3_600_000L,
                lastConsolidation = "added 1, revised 2, forgotten 0",
                lastUserActivityAt = now - 3 * 86_400_000L,
            ),
            now,
        )
        assertTrue(line.contains("12 active notes (2 pinned, 1 expiring within a day, 1 expired)"))
        assertTrue(line.contains("2 hold(s)"))
        assertTrue(line.contains("last consolidation 6 h ago (added 1, revised 2, forgotten 0)"))
        assertTrue(line.contains("last time you spoke: 3 days ago"))
    }

    @Test
    fun recentActivityIsNotAWorthwhileGap() {
        val line = MemoryPrompt.statusLine(
            MemoryStatus(activeNotes = 1, lastUserActivityAt = now - 60_000L),
            now,
        )
        assertTrue(!line.contains("last time you spoke"))
    }

    @Test
    fun staleCoreIsMarkedAsPossiblyStale() {
        val context = MemoryPrompt.context(
            core = CoreMemory(user = "用户在北京", updatedAt = now - 3 * 86_400_000L),
            primed = emptyList(),
            now = now,
        )
        assertTrue(context != null && context.contains("updated 3d ago - may be stale"))
    }

    @Test
    fun statusAloneStillProducesTheAlwaysOnBlock() {
        val context = MemoryPrompt.context(
            core = null,
            primed = emptyList(),
            now = now,
            status = MemoryStatus(activeNotes = 4),
        )
        assertTrue(context != null && context.contains("Memory state: 4 active notes"))
        assertNull(
            MemoryPrompt.context(core = null, primed = emptyList(), now = now, status = null),
        )
        assertEquals(
            true,
            MemoryPrompt.context(
                core = CoreMemory(user = "x"),
                primed = emptyList(),
                now = now,
            )?.contains("About the user") == true,
        )
    }
}
