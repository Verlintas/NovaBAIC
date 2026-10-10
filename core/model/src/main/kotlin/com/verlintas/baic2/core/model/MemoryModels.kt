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

import kotlinx.serialization.Serializable

/**
 * The kinds of durable notes the agent can keep, mirroring how memory is
 * usually classified: identity (profile), stable attitudes (preference),
 * time-anchored happenings (event), intentions (plan), commitments
 * (agreement), plain durable facts (fact) and compressed history (summary).
 */
@Serializable
enum class NoteKind {
    FACT,
    PROFILE,
    PREFERENCE,
    EVENT,
    PLAN,
    AGREEMENT,
    SUMMARY,
    ;

    /** The lowercase name used on the tool/prompt wire. */
    fun wire(): String = name.lowercase()

    companion object {
        val wireNames: List<String> = NoteKind.entries.filter { it != SUMMARY }.map { it.wire() }

        fun fromWire(raw: String?): NoteKind = when (raw?.trim()?.lowercase()) {
            "profile" -> PROFILE
            "preference" -> PREFERENCE
            "event" -> EVENT
            "plan" -> PLAN
            "agreement" -> AGREEMENT
            "summary" -> SUMMARY
            else -> FACT
        }
    }
}

/**
 * Where a note came from - source monitoring. A fact the user stated himself
 * is trusted more than the model's own inference, which is trusted more than
 * text scraped from outside.
 */
@Serializable
enum class NoteSource {
    USER,
    ASSISTANT,
    EXTERNAL,
    UNKNOWN,
    ;

    fun wire(): String = name.lowercase()

    companion object {
        val wireNames: List<String> = listOf("user", "assistant", "external")

        fun fromWire(raw: String?): NoteSource = when (raw?.trim()?.lowercase()) {
            "user" -> USER
            "assistant" -> ASSISTANT
            "external" -> EXTERNAL
            else -> UNKNOWN
        }
    }
}

/**
 * One durable note. Notes point back at their source ([conversationId],
 * [messageId]) so the model can always drill into the original episode, and
 * they can supersede each other instead of silently contradicting. [entities]
 * are the people/projects/places the note is about, so memory can be reached
 * "one thing at a time" like human episodic recall.
 */
@Serializable
data class Note(
    val id: Long = 0L,
    val kind: NoteKind = NoteKind.FACT,
    val content: String,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val conversationId: Long? = null,
    val messageId: Long? = null,
    val whenAt: Long? = null,
    /**
     * Perishable facts carry a validity horizon: "I am in X right now" is
     * wrong tomorrow. Expired notes stay retrievable but rank lower and are
     * labelled historical.
     */
    val expiresAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastAccessedAt: Long = 0L,
    val accessCount: Int = 0,
    /** Synaptic strength: retrieval reconsolidates and slows the decay down. */
    val strength: Double = 1.0,
    val source: NoteSource = NoteSource.USER,
    val entities: List<String> = emptyList(),
    /** A trace the user explicitly asked to forget; re-learning is blocked. */
    val suppressed: Boolean = false,
    val supersededBy: Long? = null,
    val archived: Boolean = false,
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt in 1 until now
}

/**
 * The always-on core memory, kept in the context window at a fixed small
 * budget: who the user is, and what is going on right now. This is what keeps
 * the agent's sense of continuity stable while everything else is recalled on
 * demand.
 */
@Serializable
data class CoreMemory(
    val user: String = "",
    val context: String = "",
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = user.isBlank() && context.isBlank()
}

/** One synthetic hit of episodic recall: an original message, with a handle. */
data class MessageHit(
    val messageId: Long,
    val conversationId: Long,
    val conversationTitle: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    /** Which query cues actually matched, mirroring the notes-side explanation. */
    val matchedCues: List<String> = emptyList(),
    /** Episodic relevance within this result set; user turns weigh double. */
    val score: Double = 0.0,
)

/** An older version of a note, kept when the note was rewritten. */
data class NoteRevision(
    val id: Long = 0L,
    val content: String,
    val importance: Int = 3,
    val replacedAt: Long = 0L,
)

/** A "do not record this" directive: a promise kept as executable state. */
data class MemoryHold(
    val id: Long = 0L,
    val content: String,
    val reason: String? = null,
)

/**
 * How the user refers to something vs. the name it is stored under:
 * "妈妈" -> "张兰". Aliases feed entity recall and priming, and may be
 * pinyin/latin spellings the user is likely to type.
 */
data class MemoryAlias(
    val alias: String,
    val entity: String,
    val createdAt: Long = 0L,
)

/** One note selected for the dream-cycle self-review, with the reason. */
data class ReviewCandidate(
    val note: Note,
    val reason: String,
)

/**
 * One consolidation pass as it actually happened: when it ran, what it looked
 * at, what it changed, and whether the plan parsed. "Empty plan" and "run
 * failed" must never look the same again.
 */
data class CuratorRun(
    val id: Long = 0L,
    val ranAt: Long,
    /** idle (left foreground) | overflow (many turns) | time (long gap) | manual. */
    val trigger: String,
    val conversationId: Long? = null,
    val messages: Int = 0,
    val windowFrom: Long = 0L,
    val windowTo: Long = 0L,
    val notesScanned: Int = 0,
    val added: Int = 0,
    val revised: Int = 0,
    val forgotten: Int = 0,
    val rehearsed: Int = 0,
    /** Notes handed to the curator for the dream-cycle self-review. */
    val reviewed: Int = 0,
    val parsed: Boolean = true,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null && parsed
}
