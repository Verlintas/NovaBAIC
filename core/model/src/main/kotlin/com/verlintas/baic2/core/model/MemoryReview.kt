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

/**
 * Dream-cycle self-review: pick the traces that deserve a second look before
 * they rot unnoticed - contradictions first, then expired-but-important
 * facts, then notes that were never once recalled. Pure so the selection is
 * regression-testable; the curator decides what to do with them.
 */
object MemoryReview {

    private const val NEVER_RECALLED_DAYS = 7L
    private const val EXPIRED_MIN_IMPORTANCE = 4

    fun candidates(notes: List<Note>, now: Long, limit: Int = 8): List<ReviewCandidate> {
        val found = LinkedHashMap<Long, ReviewCandidate>()
        // 1. Contradictions between notes of the same kind; entities only
        //    prune pairs that are obviously unrelated.
        for (i in notes.indices) {
            for (j in i + 1 until notes.size) {
                val a = notes[i]
                val b = notes[j]
                if (a.kind != b.kind) continue
                if (a.entities.isNotEmpty() && b.entities.isNotEmpty() &&
                    a.entities.none { it in b.entities }
                ) {
                    continue
                }
                if (MemoryConflict.isConflict(a.content, b.content)) {
                    found.putIfAbsent(a.id, ReviewCandidate(a, "contradicts #${b.id}"))
                    found.putIfAbsent(b.id, ReviewCandidate(b, "contradicts #${a.id}"))
                }
            }
        }
        // 2. High-value facts whose time has passed: history or revision?
        notes.filter { it.isExpired(now) && it.importance >= EXPIRED_MIN_IMPORTANCE }
            .forEach { found.putIfAbsent(it.id, ReviewCandidate(it, "expired but important")) }
        // 3. Traces nobody ever woke in a week: still true, or never needed?
        notes.filter {
            it.accessCount == 0 && !it.pinned &&
                now - it.createdAt > NEVER_RECALLED_DAYS * 86_400_000L && it.importance <= 3
        }
            .sortedBy { it.createdAt }
            .forEach { found.putIfAbsent(it.id, ReviewCandidate(it, "never recalled")) }
        return found.values.take(limit)
    }
}
