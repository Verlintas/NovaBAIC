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
import kotlin.test.assertTrue

class MemoryReviewTest {

    private val now = 1_700_000_000_000L
    private val week = 8 * 86_400_000L

    private fun note(
        id: Long,
        content: String,
        kind: NoteKind = NoteKind.FACT,
        importance: Int = 3,
        pinned: Boolean = false,
        entities: List<String> = emptyList(),
        accessCount: Int = 0,
        createdAt: Long = now - week,
        expiresAt: Long? = null,
    ) = Note(
        id = id,
        kind = kind,
        content = content,
        importance = importance,
        pinned = pinned,
        entities = entities,
        accessCount = accessCount,
        createdAt = createdAt,
        updatedAt = createdAt,
        expiresAt = expiresAt,
    )

    @Test
    fun contradictionsAreReviewedFirst() {
        val candidates = MemoryReview.candidates(
            listOf(note(1, "用户在杭州"), note(2, "用户在上海"), note(3, "用户喜欢拿铁")),
            now,
        )
        assertTrue(candidates.isNotEmpty())
        assertEquals(setOf(1L, 2L), candidates.take(2).map { it.note.id }.toSet())
        assertTrue(candidates.first().reason.contains("contradicts"))
    }

    @Test
    fun expiredImportantFactsAreReviewed() {
        val candidates = MemoryReview.candidates(
            listOf(note(5, "用户目前在乌兰浩特", importance = 4, expiresAt = now - 3_600_000L)),
            now,
        )
        assertEquals(1, candidates.size)
        assertEquals("expired but important", candidates.first().reason)
    }

    @Test
    fun coldTracesAreReviewedButPinnedOrUsedOnesAreNot() {
        val candidates = MemoryReview.candidates(
            listOf(
                note(1, "冷门旧事", importance = 2),
                note(2, "被置顶的旧事", pinned = true),
                note(3, "用过一次的旧事", accessCount = 1),
                note(4, "刚写下的", createdAt = now - 1_000L),
            ),
            now,
        )
        assertEquals(listOf(1L), candidates.map { it.note.id })
        assertEquals("never recalled", candidates.first().reason)
    }

    @Test
    fun limitIsRespectedAndReviewRenderingCarriesIds() {
        val notes = (1L..20L).map { note(it, "冷门旧事 $it", importance = 2) }
        val candidates = MemoryReview.candidates(notes, now, limit = 5)
        assertEquals(5, candidates.size)
        val rendered = MemoryPrompt.review(candidates, now)
        candidates.forEach { candidate ->
            assertTrue(rendered.contains("#${candidate.note.id}"))
        }
    }
}
