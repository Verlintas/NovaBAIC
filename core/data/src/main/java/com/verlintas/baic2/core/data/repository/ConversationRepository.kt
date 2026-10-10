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

import androidx.room.withTransaction
import com.verlintas.baic2.core.data.db.Baic2Database
import com.verlintas.baic2.core.data.db.MessageSearchRow
import com.verlintas.baic2.core.data.db.SnapshotEntity
import com.verlintas.baic2.core.data.mapper.ChatMapper
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.MessageSnapshot
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.Conversation
import com.verlintas.baic2.core.model.ConversationPreview
import com.verlintas.baic2.core.model.MemoryScoring
import com.verlintas.baic2.core.model.MessageHit
import com.verlintas.baic2.core.model.ToolTrust
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ConversationRepository @Inject constructor(
    private val db: Baic2Database,
    private val mapper: ChatMapper,
) {

    fun observeConversations(): Flow<List<ConversationPreview>> =
        db.conversationDao().observeSummaries().map { list ->
            list.map { summary ->
                ConversationPreview(
                    conversation = mapper.conversationToModel(summary.conversation),
                    lastMessage = summary.lastMessage,
                )
            }
        }

    fun observeConversation(id: Long): Flow<Conversation?> =
        db.conversationDao().observeById(id).map { entity -> entity?.let(mapper::conversationToModel) }

    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        db.messageDao().observeByConversation(conversationId)
            .map { list -> list.map(mapper::messageToModel) }

    suspend fun get(id: Long): Conversation? =
        db.conversationDao().getById(id)?.let(mapper::conversationToModel)

    suspend fun getMessages(conversationId: Long): List<ChatMessage> =
        db.messageDao().getByConversation(conversationId).map(mapper::messageToModel)

    /**
     * The newest user message before [before], across all conversations:
     * "how long ago did we last talk", injected so time gaps are never
     * guessed wrong.
     */
    suspend fun lastUserMessageAtBefore(before: Long): Long? =
        db.messageDao().latestAtBefore(ChatRole.USER.name, before)

    suspend fun conversationCount(): Int = db.conversationDao().count()

    suspend fun messageCount(): Int = db.messageDao().countAll()

    /** Provider-reported token totals across all persisted messages. */
    suspend fun tokenTotals(): Pair<Long, Long> =
        db.messageDao().totalInputTokens() to db.messageDao().totalOutputTokens()

    suspend fun create(agentId: Long?, mode: AppMode, title: String): Long {
        val now = System.currentTimeMillis()
        return db.conversationDao().insert(
            mapper.conversationToEntity(
                Conversation(title = title, agentId = agentId, mode = mode, createdAt = now, updatedAt = now),
            ),
        )
    }

    suspend fun append(message: ChatMessage): Long = db.withTransaction {
        val id = db.messageDao().insert(mapper.messageToEntity(message))
        db.conversationDao().touch(message.conversationId, System.currentTimeMillis())
        id
    }

    suspend fun updateAssistantContent(messageId: Long, content: String) {
        db.messageDao().updateContent(messageId, content)
    }

    suspend fun updateToolCalls(messageId: Long, toolCalls: List<com.verlintas.baic2.core.model.ToolCall>) {
        db.messageDao().updateToolCalls(
            messageId,
            mapper.encodeToolCalls(toolCalls),
        )
    }

    suspend fun updateTitle(id: Long, title: String) {
        db.conversationDao().updateTitle(id, title, System.currentTimeMillis())
    }

    suspend fun updateMeta(id: Long, agentId: Long?, mode: AppMode) {
        db.conversationDao().updateMeta(id, agentId, mode.name, System.currentTimeMillis())
    }

    suspend fun delete(id: Long) = db.withTransaction {
        db.messageDao().deleteAllForConversation(id)
        db.conversationDao().delete(id)
    }

    suspend fun clearMessages(id: Long) {
        db.messageDao().deleteAllForConversation(id)
    }

    suspend fun deleteMessagesAfter(conversationId: Long, afterMessageId: Long) = db.withTransaction {
        db.messageDao().deleteAfter(conversationId, afterMessageId)
        // The last provider report described a history that no longer exists.
        db.messageDao().clearUsage(conversationId)
    }

    fun observeStarredMessages(): Flow<List<ChatMessage>> =
        db.messageDao().observeStarred().map { list -> list.map(mapper::messageToModel) }

    suspend fun setStarred(messageId: Long, starred: Boolean) {
        db.messageDao().updateStarred(messageId, starred)
    }

    suspend fun updateMessageContent(messageId: Long, content: String) {
        db.messageDao().updateMessageContent(messageId, content)
    }

    /**
     * Deletes a message; an assistant message also takes its orphaned tool
     * results with it so the transcript stays protocol-valid.
     */
    suspend fun deleteMessage(messageId: Long) = db.withTransaction {
        val entity = db.messageDao().getById(messageId) ?: return@withTransaction
        val message = mapper.messageToModel(entity)
        db.messageDao().deleteById(messageId)
        if (message.toolCalls.isNotEmpty()) {
            // Scoped to the conversation: Gemini synthesises call ids from the
            // tool name and can collide with other conversations.
            db.messageDao().deleteByToolCallIds(message.conversationId, message.toolCalls.map { it.id })
        }
        db.messageDao().clearUsage(message.conversationId)
    }

    /**
     * Replaces the older history range with a single summary message: the
     * oldest summarized message becomes the summary carrier so ordering and
     * ids stay stable.
     */
    /**
     * Replaces an older message range with a summary. The replaced messages are
     * archived first, so a compression is always reversible ("restore history").
     */
    suspend fun applyCompression(
        conversationId: Long,
        summaryCarrierId: Long,
        keepFromMessageId: Long,
        summary: String,
    ) = db.withTransaction {
        val archived = db.messageDao().getRange(conversationId, summaryCarrierId, keepFromMessageId)
        val archivedModels = archived.map(mapper::messageToModel)
        if (archived.isNotEmpty()) {
            db.snapshotDao().insert(
                SnapshotEntity(
                    conversationId = conversationId,
                    carrierId = summaryCarrierId,
                    keepFromMessageId = keepFromMessageId,
                    payloadJson = mapper.encodeMessages(archivedModels),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        // Taint survives summarisation: a summary that digested untrusted
        // content keeps the marker, so the guard cannot be laundered away.
        val carrierContent = if (ToolTrust.anyUntrusted(archivedModels)) {
            ToolTrust.wrap(summary)
        } else {
            summary
        }
        db.messageDao().updateRoleAndContent(summaryCarrierId, ChatRole.ASSISTANT.name, carrierContent)
        db.messageDao().deleteRange(conversationId, summaryCarrierId, keepFromMessageId)
        db.messageDao().clearUsage(conversationId)
    }

    fun observeSnapshots(conversationId: Long): Flow<List<MessageSnapshot>> =
        db.snapshotDao().observeForConversation(conversationId)
            .map { list -> list.map(mapper::snapshotToModel) }

    /**
     * Restores the messages a compression replaced and drops the summary.
     * The whole conversation is rewritten in chronological order so message
     * ids keep matching creation order (all id-based range operations rely on
     * that invariant).
     */
    suspend fun restoreSnapshot(snapshotId: Long): Boolean = db.withTransaction {
        val entity = db.snapshotDao().getById(snapshotId) ?: return@withTransaction false
        val snapshot = mapper.snapshotToModel(entity)
        val current = db.messageDao().getByConversation(snapshot.conversationId)
            .map(mapper::messageToModel)
            .filterNot { it.id == snapshot.carrierId }
        val merged = (snapshot.messages + current)
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .map { message -> mapper.messageToEntity(message.copy(id = 0L)) }
        db.messageDao().deleteAllForConversation(snapshot.conversationId)
        db.messageDao().insertAll(merged)
        db.snapshotDao().delete(snapshotId)
        true
    }

    suspend fun discardSnapshot(snapshotId: Long) = db.snapshotDao().delete(snapshotId)

    /**
     * Cue-driven episodic recall across conversations. Cues are OR-ed with a
     * match count (BM25-shaped): a message that answers two cues outranks one
     * that answers only one, recency breaks ties. Pure AND was wrong - the
     * query "火车 国庆" should still find each mention on its own.
     */
    suspend fun searchMessages(
        terms: List<String>,
        conversationQuery: String? = null,
        from: Long? = null,
        to: Long? = null,
        roleFilter: ChatRole? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): List<MessageHit> {
        val conversationIds = resolveConversationIds(conversationQuery)
        if (!conversationQuery.isNullOrBlank() && conversationIds != null && conversationIds.isEmpty()) {
            return emptyList()
        }
        val hits = LinkedHashMap<Long, Pair<MessageSearchRow, Int>>()
        if (terms.isEmpty()) {
            queryMessages(conversationIds, null, from, to, candidateLimit(limit, offset))
                .forEach { row -> hits[row.id] = row to 1 }
        } else {
            val perTerm = (candidateLimit(limit, offset) / terms.size).coerceIn(40, 200)
            terms.forEach { term ->
                queryMessages(conversationIds, likePattern(term), from, to, perTerm)
                    .forEach { row ->
                        val existing = hits[row.id]
                        hits[row.id] = row to ((existing?.second ?: 0) + 1)
                    }
            }
        }
        return hits.values.asSequence()
            .filter { it.first.content.isNotBlank() }
            .map { (row, matches) ->
                val role = runCatching { ChatRole.valueOf(row.role) }.getOrDefault(ChatRole.ASSISTANT)
                // Name the cues that actually hit so the agent can see why this
                // message surfaced, exactly like the notes side does.
                val matched = if (terms.isEmpty()) {
                    emptyList()
                } else {
                    val haystack = row.content.lowercase()
                    terms.filter { haystack.contains(it.lowercase()) }
                }
                ScoredMessage(
                    row = row,
                    role = role,
                    score = MemoryScoring.messageScore(role, matched.size.coerceAtLeast(matches)),
                    matchedCues = matched,
                )
            }
            .filter { roleFilter == null || it.role == roleFilter }
            .sortedWith(
                compareByDescending<ScoredMessage> { it.score }
                    .thenBy { if (it.role == ChatRole.USER) 0 else 1 }
                    .thenByDescending { it.row.createdAt },
            )
            .drop(offset)
            .take(limit)
            .map { scored ->
                mapper.messageHitToModel(scored.row).copy(
                    matchedCues = scored.matchedCues,
                    score = scored.score,
                )
            }
            .toList()
    }

    private data class ScoredMessage(
        val row: MessageSearchRow,
        val role: ChatRole,
        val score: Double,
        val matchedCues: List<String>,
    )

    private suspend fun queryMessages(
        conversationIds: List<Long>?,
        pattern: String?,
        from: Long?,
        to: Long?,
        limit: Int,
    ): List<MessageSearchRow> = if (conversationIds != null) {
        db.messageDao().searchMessagesIn(conversationIds, pattern, from, to, limit)
    } else {
        db.messageDao().searchMessages(pattern, from, to, limit)
    }

    /** The window around a recalled message, oldest first. */
    suspend fun readAround(conversationId: Long, anchorId: Long?, count: Int = 6): List<ChatMessage> {
        if (db.conversationDao().getById(conversationId) == null) return emptyList()
        val span = count.coerceIn(1, 30)
        if (anchorId == null) {
            return db.messageDao().latestMessages(conversationId, span)
                .asReversed()
                .map(mapper::messageToModel)
        }
        val half = (span / 2).coerceAtLeast(1)
        val before = db.messageDao().beforeAnchor(conversationId, anchorId, half).asReversed()
        val after = db.messageDao().fromAnchor(conversationId, anchorId, span - before.size)
        return (before + after).map(mapper::messageToModel)
    }

    suspend fun conversationTitle(id: Long): String? = db.conversationDao().getById(id)?.title

    private suspend fun resolveConversationIds(query: String?): List<Long>? {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { return listOf(it) }
        return db.conversationDao().findByTitle(likePattern(trimmed)).map { it.id }
    }

    private fun likePattern(term: String): String {
        val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    private fun candidateLimit(limit: Int, offset: Int): Int =
        ((offset + limit) * 8L).coerceIn(120L, 800L).toInt()
}
