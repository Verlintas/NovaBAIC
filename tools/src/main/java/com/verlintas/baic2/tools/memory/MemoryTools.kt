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

package com.verlintas.baic2.tools.memory

import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.repository.MemoryRepository
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.MemoryScoring
import com.verlintas.baic2.core.model.MemoryText
import com.verlintas.baic2.core.model.MessageHit
import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind
import com.verlintas.baic2.core.model.NoteSource
import com.verlintas.baic2.core.model.ScoredNote
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Cue-driven episodic + semantic recall. Available in every mode because it
 * touches nothing outside the agent's own memory.
 */
class MemorySearchTool(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "memory_search",
        description = "Search your long-term memory: durable notes and every past conversation. " +
            "Use it whenever the user refers to the past or before saying you don't know " +
            "something about them. Results carry ids for memory_read and memory_write.",
        parametersJson = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"keywords in the user's language; space-separated terms are ANDed. Omit to browse recent items."},
              "scope":{"type":"string","enum":["all","notes","messages"],"description":"default all"},
              "role":{"type":"string","enum":["any","user","assistant"],"description":"filter message hits by speaker; user recalls what the user themselves said"},
              "entity":{"type":"string","description":"recall everything about a person/project/place by exact name"},
              "conversation":{"type":"string","description":"limit to one conversation by title or id"},
              "from":{"type":"string","description":"lower bound, ISO date like 2026-09-01"},
              "to":{"type":"string","description":"upper bound, ISO date"},
              "at":{"type":"string","description":"time travel: reconstruct notes and messages as of this date or datetime (e.g. 2026-09-12 or 2026-09-12 14:30). Read-only: nothing is reconsolidated and revision history is used"},
              "limit":{"type":"integer","description":"1-20, default 10"},
              "offset":{"type":"integer","description":"paging"}
            }}
        """.trimIndent(),
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
        alwaysAvailable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.string("query")?.trim().orEmpty()
        val scope = arguments.string("scope")?.trim()?.lowercase() ?: "all"
        val from = MemoryText.parseWhen(arguments.string("from"))
        val to = endOfDay(arguments.string("to"))
        val at = MemoryText.parseWhen(arguments.string("at"))
        val conversation = arguments.string("conversation")?.trim()?.takeIf { it.isNotEmpty() }
        val entity = arguments.string("entity")?.trim()?.takeIf { it.isNotEmpty() }
        val roleFilter = when (arguments.string("role")?.trim()?.lowercase()) {
            "user" -> ChatRole.USER
            "assistant" -> ChatRole.ASSISTANT
            else -> null
        }
        val limit = (arguments.int("limit") ?: 10).coerceIn(1, 20)
        val offset = (arguments.int("offset") ?: 0).coerceAtLeast(0)
        val terms = MemoryText.terms(query)

        val entityNotes: List<ScoredNote> = if (entity != null && scope != "messages") {
            if (at != null) {
                // Time travel: keep entities, reconstruct content, no feedback.
                memoryRepository.recallByEntity(listOf(entity), limit = NOTES_PER_PAGE, feedback = false)
                    .mapNotNull { scored -> memoryRepository.noteAsOf(scored.note, at) }
                    .let { reconstructed -> MemoryScoring.rank(reconstructed, terms, at) }
                    .filter { terms.isEmpty() || it.hits > 0 }
            } else {
                memoryRepository.recallByEntity(listOf(entity), limit = NOTES_PER_PAGE)
            }
        } else {
            emptyList()
        }
        val cuedNotes: List<ScoredNote> = when {
            scope == "messages" -> emptyList()
            terms.isEmpty() && entity != null -> emptyList()
            at != null -> memoryRepository.recallAsOf(terms, at, NOTES_PER_PAGE, offset)
            else -> memoryRepository.recall(terms = terms, limit = NOTES_PER_PAGE, offset = offset)
        }
        val notes = (entityNotes + cuedNotes).distinctBy { it.note.id }.take(NOTES_PER_PAGE)
        val hits: List<MessageHit> = when {
            scope == "notes" -> emptyList()
            // An entity lookup with no keywords is about notes, not messages.
            terms.isEmpty() && entity != null -> emptyList()
            else -> conversationRepository.searchMessages(
                terms = terms,
                conversationQuery = conversation,
                from = from,
                to = if (at == null) to else minOf(to ?: at, at),
                roleFilter = roleFilter,
                limit = limit,
                offset = offset,
            )
        }

        if (notes.isEmpty() && hits.isEmpty()) {
            return ToolResult.Success(
                buildString {
                    append("No memories match")
                    if (query.isNotEmpty()) append(" \"").append(query).append('"')
                    if (at != null) append(" as of ").append(MemoryText.formatDateTime(at))
                    append(". Try fewer or different keywords, a wider date range, or no conversation filter.")
                    if (at != null) append(" The fact may not have been recorded yet at that time.")
                    if (at == null) closestBlock(query)?.let { append('\n').append(it) }
                },
            )
        }
        val now = at ?: System.currentTimeMillis()
        return ToolResult.Success(
            buildString {
                append("Memory recall")
                if (query.isNotEmpty()) append(" for \"").append(query).append('"')
                if (at != null) append(" as of ").append(MemoryText.formatDateTime(at))
                append(" (notes ").append(notes.size).append(", messages ").append(hits.size).append("):\n")
                if (notes.isNotEmpty()) {
                    val lead = if (notes.size >= 2) notes[0].score - notes[1].score else 0.0
                    append("\nNotes (ranked; top leads second by ")
                        .append("%.2f".format(java.util.Locale.ROOT, lead))
                        .append("; scores are relative within this result set; ")
                        .append("use the #id with memory_write replaces= to correct one):\n")
                    if (at != null) {
                        append("(reconstructed from revision history; this retrieval does not reconsolidate anything)\n")
                    }
                    notes.forEachIndexed { index, scored ->
                        append("- ")
                        if (scored.spread) {
                            if (scored.spreadHops >= 2) {
                                append("(2-hop via #").append(scored.spreadFrom).append(") ")
                            } else {
                                append("(associated from #").append(scored.spreadFrom)
                                    .append(", link w").append(scored.linkWeight.toInt()).append(") ")
                            }
                        }
                        append(
                            noteLine(
                                scored = scored,
                                cueCount = terms.size,
                                now = now,
                                rank = index + 1,
                                total = notes.size,
                                asOf = at,
                            ),
                        ).append('\n')
                    }
                } else {
                    if (hits.isNotEmpty() && scope != "messages") {
                        append("\nNo notes matched; if this is worth keeping, consider memory_write.\n")
                    }
                    if (at == null) closestBlock(query)?.let { append('\n').append(it).append('\n') }
                }
                if (entity != null && at == null) {
                    entityTimeline(entity, now)?.let { append('\n').append(it).append('\n') }
                }
                if (hits.isNotEmpty()) {
                    append("\nMessages (ranked; user turns weigh double; scores relative within this set; ")
                        .append("use memory_read with conversation_id + message_id for more):\n")
                    hits.forEachIndexed { index, hit ->
                        val cues = if (terms.isEmpty()) {
                            "no keywords - browsing recent"
                        } else {
                            "keyword match ${hit.matchedCues.size}/${terms.size}" +
                                hit.matchedCues.joinToString(prefix = " (", postfix = ")")
                        }
                        // Same vocabulary as the notes side: thin coverage is
                        // flagged weak so an assistant echo cannot masquerade
                        // as a strong episodic hit.
                        val weak = if (terms.isNotEmpty() && hit.matchedCues.size * 2 < terms.size) {
                            " · weak match, reference only"
                        } else {
                            ""
                        }
                        append("- rank ").append(index + 1).append('/').append(hits.size)
                            .append(" · score ")
                            .append("%.2f".format(java.util.Locale.ROOT, hit.score))
                            .append(weak)
                            .append(" · ").append(cues)
                            .append(" · ").append(MemoryText.formatDateTime(hit.createdAt))
                            .append(" (").append(MemoryText.relativeTime(now, hit.createdAt)).append(")")
                            .append(" · \"").append(hit.conversationTitle.ifBlank { "untitled" })
                            .append("\" conv #").append(hit.conversationId)
                            .append(" · ").append(hit.role.name.lowercase()).append(" #").append(hit.messageId)
                            .append(": ").append(MemoryText.snippet(hit.content, terms)).append('\n')
                    }
                }
            }.trim(),
        )
    }

    /** Never an empty exit: the closest notes by text similarity, clearly weak. */
    /**
     * "关于某人的一切": current facts first, then the history behind them.
     * Read-only; aliases resolve to the stored name before querying.
     */
    private suspend fun entityTimeline(entity: String, now: Long): String? {
        val all = memoryRepository.notesForEntity(entity, limit = 80)
        if (all.isEmpty()) return null
        val aliases = memoryRepository.aliases()
            .filter { it.entity.equals(entity, ignoreCase = true) }
            .map { it.alias }
        val current = all.filter { !it.isExpired(now) }
            .sortedWith(
                compareByDescending<Note> { it.pinned }
                    .thenByDescending { it.importance }
                    .thenByDescending { it.updatedAt },
            )
            .take(6)
        val currentIds = current.map { it.id }.toSet()
        val history = all.filter { it.id !in currentIds }
            .sortedByDescending { it.whenAt ?: it.updatedAt }
            .take(8)
        return buildString {
            append("Timeline for ").append(entity)
            if (aliases.isNotEmpty()) {
                append(" (also known as: ").append(aliases.joinToString(", ")).append(')')
            }
            append(" - ").append(all.size).append(" notes:\n")
            if (current.isNotEmpty()) {
                append("Current:\n")
                current.forEach { note ->
                    append("- #").append(note.id).append(" [").append(note.kind.wire())
                        .append(" i").append(note.importance).append("] ")
                        .append(note.content.replace('\n', ' ').take(160)).append('\n')
                }
            }
            if (history.isNotEmpty()) {
                append("History (newest first):\n")
                history.forEach { note ->
                    val whenLabel = note.whenAt?.let { MemoryText.dateOnly(it) }
                        ?: MemoryText.dateOnly(note.updatedAt)
                    append("- ").append(whenLabel).append(" #").append(note.id)
                        .append(" [").append(note.kind.wire()).append("] ")
                        .append(note.content.replace('\n', ' ').take(160)).append('\n')
                }
            }
        }.trim()
    }

    private suspend fun closestBlock(query: String): String? {
        if (query.isBlank()) return null
        val closest = memoryRepository.closestNotes(query)
        if (closest.isEmpty()) return null
        return buildString {
            append("Closest notes by text similarity (weak, for reference only):\n")
            closest.forEach { (note, similarity) ->
                append("- #").append(note.id)
                    .append(" (similarity ").append("%.2f".format(java.util.Locale.ROOT, similarity)).append("): ")
                    .append(MemoryText.snippet(note.content, emptyList(), 140)).append('\n')
            }
        }.trim()
    }

    /**
     * Every recalled note states why it surfaced: rank in this result set,
     * natural-language path, cue coverage, strength, retrievability, last
     * recall and where it came from - so the agent can weigh a strongly-held
     * old memory against a fresh keyword hit, and can go back to the source.
     * With [asOf] set the note is a historical reconstruction: changes made
     * after that moment are marked.
     */
    private fun noteLine(
        scored: ScoredNote,
        cueCount: Int,
        now: Long,
        rank: Int,
        total: Int,
        asOf: Long? = null,
    ): String {
        val note = scored.note
        val recall = (MemoryScoring.retrievability(note, now) * 100).toInt()
        val last = if (note.lastAccessedAt > 0) {
            MemoryText.relativeTime(now, note.lastAccessedAt)
        } else {
            "never"
        }
        val cues = if (cueCount == 0) {
            "no keywords - browsing recent"
        } else {
            "keyword match ${scored.hits}/$cueCount" +
                scored.matchedCues.joinToString(prefix = " (", postfix = ")")
        }
        val weak = if (!scored.spread && cueCount > 0 && scored.score < WEAK_MATCH_SCORE) {
            " · weak match, reference only"
        } else {
            ""
        }
        val score = "%.2f".format(java.util.Locale.ROOT, scored.score)
        val strength = "%.1f".format(java.util.Locale.ROOT, note.strength)
        val source = if (note.source != NoteSource.USER) " · src ${note.source.wire()}" else ""
        val entities = if (note.entities.isNotEmpty()) {
            note.entities.joinToString(prefix = " · @", separator = "@")
        } else {
            ""
        }
        val expiry = when (val expiresAt = note.expiresAt) {
            null -> ""
            else -> if (note.isExpired(now)) {
                " · expired ${MemoryText.relativeTime(now, expiresAt)} (historical)"
            } else {
                " · valid until ${MemoryText.formatDateTime(expiresAt)}"
            }
        }
        // Evidence: where the note came from, so it can be verified via
        // memory_read instead of being trusted blindly.
        val evidence = buildString {
            note.conversationId?.let { append(" · from conv #").append(it) }
            note.messageId?.let { append(" · msg #").append(it) }
        }
        val changedSince = when {
            asOf == null -> ""
            note.archived -> " · archived since (no longer active now)"
            note.updatedAt > asOf -> " · later revised (this was the version then)"
            else -> ""
        }
        return "#${note.id} [${note.kind.wire()} i${note.importance}$source$entities" +
            " · rank $rank/$total · score $score$weak · $cues$expiry$changedSince" +
            " · strength $strength · recall $recall% · last recalled $last$evidence] " +
            note.content.replace('\n', ' ')
    }

    /** A bare date as `to` means the whole day. */
    private fun endOfDay(raw: String?): Long? {
        val value = MemoryText.parseWhen(raw) ?: return null
        return if (raw != null && raw.trim().length <= 10) value + 86_400_000L - 1 else value
    }

    private companion object {
        const val NOTES_PER_PAGE = 8
        const val WEAK_MATCH_SCORE = 0.5
    }
}

/** Opens the original episode around a recalled message. */
class MemoryReadTool(
    private val conversationRepository: ConversationRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "memory_read",
        description = "Read a conversation window around a message id returned by memory_search, " +
            "or the latest messages of a conversation.",
        parametersJson = """
            {"type":"object","properties":{
              "conversation_id":{"type":"integer","description":"from memory_search"},
              "message_id":{"type":"integer","description":"anchor; omit for the latest messages"},
              "count":{"type":"integer","description":"2-20, default 6"}
            },"required":["conversation_id"]}
        """.trimIndent(),
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
        alwaysAvailable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val conversationId = arguments.long("conversation_id")
            ?: return ToolResult.Failure("Missing 'conversation_id' argument")
        val anchor = arguments.long("message_id")
        val count = (arguments.int("count") ?: 6).coerceIn(2, 20)
        val title = conversationRepository.conversationTitle(conversationId)
            ?: return ToolResult.Failure("Conversation $conversationId does not exist.")
        val messages = conversationRepository.readAround(conversationId, anchor, count)
        if (messages.isEmpty()) {
            return ToolResult.Success("Conversation \"$title\" ($conversationId) has no messages.")
        }
        return ToolResult.Success(
            buildString {
                append("Conversation \"").append(title.ifBlank { "untitled" })
                    .append("\" (#").append(conversationId).append(')')
                if (anchor != null) append(", around message #").append(anchor)
                append(":\n")
                messages.forEach { message ->
                    append('[').append(MemoryText.formatDateTime(message.createdAt)).append("] ")
                    append(message.role.name.lowercase()).append(": ")
                    append(message.content.replace(Regex("\\s+"), " ").take(400)).append('\n')
                }
            }.trim(),
        )
    }
}

/** Deliberate note-taking: the agent decides what deserves to persist. */
class MemoryWriteTool(
    private val memoryRepository: MemoryRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "memory_write",
        description = "Keep a durable note in long-term memory: stable preferences, ongoing " +
            "projects, agreements, important dates, corrections. Never small talk, one-off " +
            "details, or secrets. Pass replaces=<note id> to update an existing note in place " +
            "(the id stays stable; the old version goes to history). Use notes=[...] to write " +
            "several notes in one call.",
        parametersJson = """
            {"type":"object","properties":{
              "content":{"type":"string","description":"the durable fact, one line, in the user's language"},
              "kind":{"type":"string","enum":["profile","preference","event","plan","agreement","fact"],"description":"default fact"},
              "importance":{"type":"integer","description":"1-5, default 3"},
              "when":{"type":"string","description":"the date this refers to, if any, like 2026-09-12"},
              "expires":{"type":"string","description":"for perishable facts only (current location, temporary states): a duration like 12h/3d or a date; expired notes rank lower and are labelled historical"},
              "source":{"type":"string","enum":["user","assistant","external"],"description":"who the fact comes from; default user (what the user said themselves)"},
              "entities":{"type":"array","items":{"type":"string"},"description":"people/projects/places this is about, exact names, at most 6"},
              "replaces":{"type":"integer","description":"update this note in place; the id stays stable and the previous version is kept in history"},
              "notes":{"type":"array","items":{"type":"object","properties":{
                  "content":{"type":"string"},
                  "kind":{"type":"string","enum":["profile","preference","event","plan","agreement","fact"]},
                  "importance":{"type":"integer"},
                  "when":{"type":"string"},
                  "expires":{"type":"string"},
                  "source":{"type":"string","enum":["user","assistant","external"]},
                  "entities":{"type":"array","items":{"type":"string"}}
                },"required":["content"]},"description":"batch write: several notes in one call; content/replaces are ignored when present"}
            }}
        """.trimIndent(),
        alwaysAvailable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val batch = arguments["notes"] as? JsonArray
        if (batch != null && batch.isNotEmpty()) {
            return writeBatch(batch, context)
        }
        val content = arguments.string("content")?.trim().orEmpty()
        if (content.isEmpty()) {
            return ToolResult.Failure("Provide 'content' (single note) or 'notes' (batch).")
        }
        if (content.length > MAX_CONTENT_CHARS) {
            return ToolResult.Failure(
                "Note is too long (${content.length} chars, max $MAX_CONTENT_CHARS). Split it or shorten it.",
            )
        }
        val kindRaw = arguments.string("kind")?.trim()?.takeIf { it.isNotEmpty() }
        val kind = NoteKind.fromWire(kindRaw)
        val importanceRaw = arguments.int("importance")
        val importance = (importanceRaw ?: 3).coerceIn(1, 5)
        val whenRaw = arguments.string("when")?.trim()?.takeIf { it.isNotEmpty() }
        val whenAt = whenRaw?.let(MemoryText::parseWhen)
        val expiresRaw = arguments.string("expires")?.trim()?.takeIf { it.isNotEmpty() }
        val expiresAt = expiresRaw?.let(MemoryText::parseExpiry)
        val replaces = arguments.long("replaces")?.takeIf { it > 0 }
        val sourceRaw = arguments.string("source")?.trim()?.takeIf { it.isNotEmpty() }
        val source = if (sourceRaw == null) NoteSource.USER else NoteSource.fromWire(sourceRaw)
        val entities = (arguments["entities"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_ENTITIES)

        // replaces is deterministic: in-place update, id never changes.
        // Unspecified fields keep their current values instead of defaults.
        if (replaces != null) {
            val updated = memoryRepository.replaceNote(
                id = replaces,
                content = content,
                importance = importanceRaw,
                entities = entities,
                kind = kindRaw?.let(NoteKind::fromWire),
                expiresAt = expiresAt,
                messageId = context.run.triggerMessageId,
            )
            return if (updated != null) {
                ToolResult.Success(
                    "Updated note #${updated.note.id} in place " +
                        "(was: \"${updated.previousContent.take(120)}\"; " +
                        "id stable, previous version kept in history).",
                )
            } else {
                ToolResult.Failure(
                    "replaces=$replaces does not exist; nothing was updated. Search for the right id first.",
                )
            }
        }

        return when (val outcome = memoryRepository.addNote(
            kind = kind,
            content = content,
            importance = importance,
            conversationId = context.run.conversationId,
            messageId = context.run.triggerMessageId,
            whenAt = whenAt,
            source = source,
            entities = entities,
            expiresAt = expiresAt,
        )) {
            is MemoryRepository.AddOutcome.Duplicate ->
                ToolResult.Success(
                    "Already known as note #${outcome.id}; not saved again. " +
                        "Use memory_write with replaces=${outcome.id} if this corrects it.",
                )

            is MemoryRepository.AddOutcome.Suppressed ->
                ToolResult.Success(
                    "Refused: this was explicitly forgotten before (note #${outcome.id}). " +
                        "Confirm with the user before re-learning it.",
                )

            is MemoryRepository.AddOutcome.Held ->
                ToolResult.Success(
                    "Refused: the user asked that this never be recorded (hold #${outcome.holdId}). " +
                        "Do not try again unless the user lifts the hold explicitly.",
                )

            is MemoryRepository.AddOutcome.Merged ->
                ToolResult.Success(
                    "Reconsolidated into note #${outcome.id} " +
                        "(was: \"${outcome.previousContent.take(120)}\"; " +
                        "near-duplicate updated, strength reinforced, previous version kept in history).",
                )

            is MemoryRepository.AddOutcome.Saved ->
                ToolResult.Success(
                    buildString {
                        append("Saved note #").append(outcome.id)
                            .append(" (").append(kind.wire())
                            .append(if (source != NoteSource.USER) ", ${source.wire()}" else "")
                            .append(", importance ").append(importance).append(')')
                        if (whenAt != null) append(" for ").append(MemoryText.dateOnly(whenAt))
                        if (whenRaw != null && whenAt == null) {
                            append(" (could not parse when=\"").append(whenRaw).append("\"; ignored)")
                        }
                        if (expiresAt != null) {
                            append(", expires ").append(MemoryText.formatDateTime(expiresAt))
                        }
                        if (expiresRaw != null && expiresAt == null) {
                            append(" (could not parse expires=\"").append(expiresRaw).append("\"; ignored)")
                        }
                        if (outcome.similarIds.isNotEmpty()) {
                            append(". Similar note #").append(outcome.similarIds.joinToString("#"))
                                .append(" already exists - resend with replaces= if this updates it")
                        }
                        outcome.conflicts.take(2).forEach { conflict ->
                            append("\nPossible conflict with note #").append(conflict.noteId)
                                .append(" (\"").append(conflict.existingContent.take(80)).append("\": ")
                                .append(conflict.reason).append("). If this corrects it, resend with replaces=")
                                .append(conflict.noteId)
                                .append("; if it is genuinely separate, keep both and say why.")
                        }
                        append('.')
                    },
                )
        }
    }

    private suspend fun writeBatch(batch: JsonArray, context: ToolContext): ToolResult {
        val lines = mutableListOf<String>()
        batch.take(MAX_BATCH).forEachIndexed { index, item ->
            val objectItem = item as? JsonObject ?: return@forEachIndexed
            val itemContent = objectItem.string("content")?.trim().orEmpty()
            if (itemContent.isEmpty() || itemContent.length > MAX_CONTENT_CHARS) {
                lines += "${index + 1}. skipped (missing or too long)"
                return@forEachIndexed
            }
            val itemKind = NoteKind.fromWire(objectItem.string("kind"))
            val itemImportance = ((objectItem["importance"] as? JsonPrimitive)?.intOrNull ?: 3)
                .coerceIn(1, 5)
            val itemSource = objectItem.string("source")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(NoteSource::fromWire) ?: NoteSource.USER
            val itemEntities = (objectItem["entities"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_ENTITIES)
            val itemWhen = MemoryText.parseWhen(objectItem.string("when"))
            val itemExpires = MemoryText.parseExpiry(objectItem.string("expires"))
            val outcome = memoryRepository.addNote(
                kind = itemKind,
                content = itemContent,
                importance = itemImportance,
                conversationId = context.run.conversationId,
                messageId = context.run.triggerMessageId,
                whenAt = itemWhen,
                source = itemSource,
                entities = itemEntities,
                expiresAt = itemExpires,
            )
            lines += when (outcome) {
                is MemoryRepository.AddOutcome.Saved -> {
                    val conflictIds = outcome.conflicts.joinToString("#") { it.noteId.toString() }
                    "#${outcome.id} saved (${itemKind.wire()})" +
                        if (outcome.conflicts.isEmpty()) "" else " - possible conflict with #$conflictIds"
                }
                is MemoryRepository.AddOutcome.Duplicate -> "#${outcome.id} already known"
                is MemoryRepository.AddOutcome.Merged ->
                    "#${outcome.id} reconsolidated (was: \"${outcome.previousContent.take(60)}\")"
                is MemoryRepository.AddOutcome.Suppressed -> "refused (forgotten before, #${outcome.id})"
                is MemoryRepository.AddOutcome.Held -> "refused (user asked not to record, hold #${outcome.holdId})"
            }
        }
        return ToolResult.Success("Batch write:\n" + lines.joinToString("\n"))
    }

    private companion object {
        const val MAX_CONTENT_CHARS = 400
        const val MAX_ENTITIES = 6
        const val MAX_BATCH = 10
    }
}

/** Active suppression: the user asked to forget, the agent archives the trace. */
class MemoryForgetTool(
    private val memoryRepository: MemoryRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "memory_forget",
        description = "Archive memories the user asked to forget (soft delete; raw conversations " +
            "are never touched). Find notes by id from memory_search, or by query. Confirm what " +
            "will be forgotten with the user before calling this. Pass hard=true only when the " +
            "user explicitly asks to erase it permanently - that destroys the row, its history " +
            "and its links, and cannot be undone.",
        parametersJson = """
            {"type":"object","properties":{
              "ids":{"type":"array","items":{"type":"integer"},"description":"note ids to forget"},
              "query":{"type":"string","description":"keywords to find notes when ids are unknown"},
              "hard":{"type":"boolean","description":"true = permanent erase (only on explicit user request)"},
              "reason":{"type":"string","description":"short reason, for the record"}
            }}
        """.trimIndent(),
        alwaysAvailable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val ids = (arguments["ids"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull?.takeIf { value -> value > 0 } }
            .take(MAX_FORGET)
        val query = arguments.string("query")?.trim().orEmpty()
        val hard = (arguments["hard"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (ids.isEmpty() && query.isEmpty()) {
            return ToolResult.Failure("Provide 'ids' or 'query'")
        }
        val targets = LinkedHashMap<Long, Note>()
        ids.forEach { id -> memoryRepository.noteById(id)?.let { targets[it.id] = it } }
        if (targets.isEmpty() && query.isNotEmpty()) {
            memoryRepository.recall(
                terms = MemoryText.terms(query),
                limit = 3,
                spread = false,
                feedback = false,
            ).forEach { scored -> targets[scored.note.id] = scored.note }
        }
        if (targets.isEmpty()) return ToolResult.Success("No matching notes to forget.")
        targets.values.forEach { note ->
            if (hard) memoryRepository.purge(note.id) else memoryRepository.suppress(note.id)
        }
        return ToolResult.Success(
            buildString {
                if (hard) {
                    append("Erased ").append(targets.size).append(" note(s) permanently:\n")
                } else {
                    append("Forgotten ").append(targets.size)
                        .append(" note(s); re-learning is blocked until the user confirms:\n")
                }
                targets.values.forEach { note ->
                    append("- #").append(note.id).append(' ').append(note.content.replace('\n', ' '))
                        .append('\n')
                }
            }.trim(),
        )
    }

    private companion object {
        const val MAX_FORGET = 10
    }
}

/** A promise stored as state: "do not record this". */
class MemoryHoldTool(
    private val memoryRepository: MemoryRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "memory_hold",
        description = "Record that the user does not want something kept in memory " +
            "(\"don't write this down\", \"off the record\"). The hold is permanent state: " +
            "memory_write and the memory curator will refuse anything similar until it is lifted. " +
            "Use it immediately when the user asks; later recording is exactly the failure to avoid.",
        parametersJson = """
            {"type":"object","properties":{
              "content":{"type":"string","description":"the topic or fact that must not be recorded, in the user's words"},
              "reason":{"type":"string","description":"short reason, for the record"}
            },"required":["content"]}
        """.trimIndent(),
        alwaysAvailable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val content = arguments.string("content")?.trim().orEmpty()
        if (content.isEmpty()) return ToolResult.Failure("Missing 'content' argument")
        val reason = arguments.string("reason")?.trim()?.takeIf { it.isNotEmpty() }
        val id = memoryRepository.addHold(content, reason)
        return ToolResult.Success(
            "Hold #$id created: nothing similar will be recorded (similarity >= 0.7) until the user lifts it. " +
                "The user can see and lift holds in Library -> Biomimetic memory.",
        )
    }
}

/** "妈妈" -> "张兰": keep how the user refers to people and things. */
class MemoryAliasTool(
    private val memoryRepository: MemoryRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "memory_alias",
        description = "Map how the user refers to someone or something to the canonical name it " +
            "is stored under (妈妈 -> 张兰). Aliases feed entity recall and priming, and can be " +
            "pinyin or latin spellings the user is likely to type. Only store aliases the user " +
            "actually uses; don't invent them. Use remove=true to delete one.",
        parametersJson = """
            {"type":"object","properties":{
              "alias":{"type":"string","description":"what the user says, e.g. 妈妈 or zhanglan"},
              "entity":{"type":"string","description":"the stored name it refers to, e.g. 张兰"},
              "remove":{"type":"boolean","description":"set true to delete this alias instead"}
            },"required":["alias"]}
        """.trimIndent(),
        alwaysAvailable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val alias = arguments.string("alias")?.trim().orEmpty()
        if (alias.isEmpty()) return ToolResult.Failure("Missing 'alias' argument")
        val remove = (arguments["remove"] as? JsonPrimitive)?.booleanOrNull == true
        if (remove) {
            return if (memoryRepository.removeAlias(alias)) {
                ToolResult.Success("Alias '$alias' removed.")
            } else {
                ToolResult.Failure("No alias '$alias' is stored.")
            }
        }
        val entity = arguments.string("entity")?.trim().orEmpty()
        if (entity.isEmpty()) {
            return ToolResult.Failure("Provide 'entity' (the stored name) or remove=true.")
        }
        return if (memoryRepository.setAlias(alias, entity)) {
            ToolResult.Success(
                "Alias stored: '$alias' -> '$entity'. Recall and priming now treat them as one.",
            )
        } else {
            ToolResult.Failure("Alias and entity must be non-empty and different.")
        }
    }
}

/** Fix the always-on core the moment the user corrects it. */
class CoreMemoryUpdateTool(
    private val memoryRepository: MemoryRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "core_memory_update",
        description = "Update the always-on core memory: what you know about the user, or what is " +
            "going on right now. Use it the moment the user corrects those facts, so a wrong line " +
            "stops being injected immediately instead of waiting for the curator. Send the full " +
            "corrected text for the slot (the current text is visible in your context).",
        parametersJson = """
            {"type":"object","properties":{
              "slot":{"type":"string","enum":["user","context"],"description":"user = stable facts about the person; context = what is ongoing"},
              "set":{"type":"string","description":"full replacement text for the slot"},
              "append":{"type":"string","description":"append one line instead of replacing"}
            },"required":["slot"]}
        """.trimIndent(),
        alwaysAvailable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val slot = arguments.string("slot")?.trim()?.lowercase()
            ?: return ToolResult.Failure("Missing 'slot' argument")
        if (slot != "user" && slot != "context") {
            return ToolResult.Failure("slot must be 'user' or 'context'")
        }
        val set = arguments.string("set")?.trim()?.takeIf { it.isNotEmpty() }
        val appendLine = arguments.string("append")?.trim()?.takeIf { it.isNotEmpty() }
        if (set == null && appendLine == null) {
            return ToolResult.Failure("Provide 'set' (replacement) or 'append' (one line).")
        }
        val current = memoryRepository.getCore()
        val text = when {
            set != null -> set
            else -> {
                val existing = if (slot == "user") current.user.trim() else current.context.trim()
                val line = appendLine.orEmpty()
                if (existing.isEmpty()) line else "$existing\n$line"
            }
        }.take(MAX_SLOT_CHARS)
        if (slot == "user") {
            memoryRepository.setCore(user = text)
        } else {
            memoryRepository.setCore(context = text)
        }
        return ToolResult.Success(
            "Core memory ($slot) ${if (set != null) "replaced" else "appended"}; $text.length chars now.",
        )
    }

    private companion object {
        const val MAX_SLOT_CHARS = 600
    }
}

/** One-call orientation over the whole memory system. */
class MemoryOverviewTool(
    private val memoryRepository: MemoryRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "memory_overview",
        description = "A quick map of your memory: core blocks, note counts by kind, most recent " +
            "notes, top entities and active holds. Use it to orient before deciding what to search.",
        parametersJson = """
            {"type":"object","properties":{
              "limit":{"type":"integer","description":"recent notes to list, 1-20, default 8"}
            }}
        """.trimIndent(),
        readOnly = true,
        danger = DangerLevel.LOW,
        alwaysAvailable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val limit = (arguments.int("limit") ?: 8).coerceIn(1, 20)
        val core = memoryRepository.getCore()
        val notes = memoryRepository.listActive(500)
        val holds = memoryRepository.listHolds()
        val aliases = memoryRepository.aliases()
        val now = System.currentTimeMillis()
        val counts = notes.groupingBy { it.kind }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key.wire()} ${it.value}" }
        val entities = notes.flatMap { it.entities }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(10)
            .joinToString(" ") { "@${it.key}(${it.value})" }
        val recent = notes.sortedByDescending { it.updatedAt }.take(limit)
        return ToolResult.Success(
            buildString {
                append("Memory overview\n")
                append("Core (updated ").append(
                    if (core.updatedAt > 0) MemoryText.dateOnly(core.updatedAt) else "never",
                ).append("):\n")
                append("- about the user: ").append(core.user.trim().ifBlank { "(empty)" }.replace('\n', ' '))
                    .append('\n')
                append("- ongoing: ").append(core.context.trim().ifBlank { "(empty)" }.replace('\n', ' '))
                    .append('\n')
                append("Active notes: ").append(notes.size)
                if (counts.isNotEmpty()) append(" (").append(counts).append(')')
                append('\n')
                if (entities.isNotEmpty()) append("Entities: ").append(entities).append('\n')
                if (aliases.isNotEmpty()) {
                    append("Aliases: ").append(
                        aliases.take(10).joinToString(", ") { "${it.alias}→${it.entity}" },
                    ).append('\n')
                }
                append("Holds (never record): ").append(holds.size)
                if (holds.isNotEmpty()) {
                    append(" - ").append(holds.take(3).joinToString("; ") { it.content })
                }
                append("\nRecent notes:\n")
                recent.forEach { note ->
                    append("- #").append(note.id).append(" [").append(note.kind.wire())
                        .append(" i").append(note.importance)
                        .append(", ").append(MemoryText.relativeTime(now, note.updatedAt))
                        .append("] ").append(note.content.replace('\n', ' ')).append('\n')
                }
            }.trim(),
        )
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull
