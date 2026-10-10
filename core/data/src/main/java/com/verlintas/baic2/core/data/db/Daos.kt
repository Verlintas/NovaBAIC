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

package com.verlintas.baic2.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {

    @Query("SELECT * FROM agents ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents ORDER BY createdAt ASC")
    suspend fun getAll(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getById(id: Long): AgentEntity?

    @Query("SELECT * FROM agents WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): AgentEntity?

    @Query("SELECT * FROM agents WHERE isDefault = 1 LIMIT 1")
    fun observeDefault(): Flow<AgentEntity?>

    @Insert
    suspend fun insert(entity: AgentEntity): Long

    @Update
    suspend fun update(entity: AgentEntity)

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun count(): Int

    @Query("UPDATE agents SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE agents SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT c.*, (
            SELECT m.content FROM messages m
            WHERE m.conversationId = c.id
            ORDER BY m.id DESC LIMIT 1
        ) AS lastMessage
        FROM conversations c
        ORDER BY c.updatedAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE title LIKE :pattern ESCAPE '\\' ORDER BY updatedAt DESC LIMIT 10")
    suspend fun findByTitle(pattern: String): List<ConversationEntity>

    @Insert
    suspend fun insert(entity: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, now: Long)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Query("UPDATE conversations SET agentId = :agentId, mode = :mode, updatedAt = :now WHERE id = :id")
    suspend fun updateMeta(id: Long, agentId: Long?, mode: String, now: Long)

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeByConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getByConversation(conversationId: Long): List<MessageEntity>

    /** Newest message of a role strictly before [before], across conversations. */
    @Query("SELECT MAX(createdAt) FROM messages WHERE role = :role AND createdAt < :before")
    suspend fun latestAtBefore(role: String, before: Long): Long?

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND id >= :afterId AND id < :beforeId ORDER BY id ASC",
    )
    suspend fun getRange(conversationId: Long, afterId: Long, beforeId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    @Insert
    suspend fun insertAll(entities: List<MessageEntity>)

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Query("UPDATE messages SET toolCallsJson = :toolCallsJson WHERE id = :id")
    suspend fun updateToolCalls(id: Long, toolCallsJson: String)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun updateStarred(id: Long, starred: Boolean)

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND toolCallId IN (:ids)")
    suspend fun deleteByToolCallIds(conversationId: Long, ids: List<String>)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("UPDATE messages SET role = :role, content = :content WHERE id = :id")
    suspend fun updateRoleAndContent(id: Long, role: String, content: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :afterId AND id < :beforeId")
    suspend fun deleteRange(conversationId: Long, afterId: Long, beforeId: Long)

    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN conversations c ON c.id = m.conversationId
        WHERE m.starred = 1
        ORDER BY m.id DESC
        """,
    )
    fun observeStarred(): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET usageInput = NULL, usageOutput = NULL WHERE conversationId = :conversationId")
    suspend fun clearUsage(conversationId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllForConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :afterId")
    suspend fun deleteAfter(conversationId: Long, afterId: Long)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int

    @Query("SELECT COALESCE(SUM(usageInput), 0) FROM messages")
    suspend fun totalInputTokens(): Long

    @Query("SELECT COALESCE(SUM(usageOutput), 0) FROM messages")
    suspend fun totalOutputTokens(): Long

    /**
     * Cross-conversation recall. Tool traffic is skipped: episodes are what
     * a person remembers, not their shell commands. The pattern only prefilters
     * on the first cue; the repository narrows with the remaining terms.
     */
    @Query(
        """
        SELECT m.id AS id, m.conversationId AS conversationId, c.title AS conversationTitle,
               m.role AS role, m.content AS content, m.createdAt AS createdAt
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversationId
        WHERE m.role <> 'TOOL'
          AND (:pattern IS NULL OR m.content LIKE :pattern ESCAPE '\')
          AND (:from IS NULL OR m.createdAt >= :from)
          AND (:to IS NULL OR m.createdAt <= :to)
        ORDER BY m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchMessages(
        pattern: String?,
        from: Long?,
        to: Long?,
        limit: Int,
    ): List<MessageSearchRow>

    @Query(
        """
        SELECT m.id AS id, m.conversationId AS conversationId, c.title AS conversationTitle,
               m.role AS role, m.content AS content, m.createdAt AS createdAt
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversationId
        WHERE m.role <> 'TOOL'
          AND m.conversationId IN (:conversationIds)
          AND (:pattern IS NULL OR m.content LIKE :pattern ESCAPE '\')
          AND (:from IS NULL OR m.createdAt >= :from)
          AND (:to IS NULL OR m.createdAt <= :to)
        ORDER BY m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchMessagesIn(
        conversationIds: List<Long>,
        pattern: String?,
        from: Long?,
        to: Long?,
        limit: Int,
    ): List<MessageSearchRow>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND id >= :anchorId ORDER BY id ASC LIMIT :limit",
    )
    suspend fun fromAnchor(conversationId: Long, anchorId: Long, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND id < :anchorId ORDER BY id DESC LIMIT :limit",
    )
    suspend fun beforeAnchor(conversationId: Long, anchorId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit")
    suspend fun latestMessages(conversationId: Long, limit: Int): List<MessageEntity>
}

@Dao
interface ScheduledTaskDao {

    @Query("SELECT * FROM scheduled_tasks ORDER BY id ASC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1")
    suspend fun getEnabled(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getById(id: Long): ScheduledTaskEntity?

    @Insert
    suspend fun insert(entity: ScheduledTaskEntity): Long

    @Update
    suspend fun update(entity: ScheduledTaskEntity)

    @Query("UPDATE scheduled_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query(
        "UPDATE scheduled_tasks SET conversationId = :conversationId, lastRunAt = :lastRunAt, " +
            "nextRunAt = :nextRunAt, lastResult = :lastResult WHERE id = :id",
    )
    suspend fun updateAfterRun(
        id: Long,
        conversationId: Long?,
        lastRunAt: Long,
        nextRunAt: Long,
        lastResult: String?,
    )

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SnapshotDao {

    @Insert
    suspend fun insert(entity: SnapshotEntity): Long

    @Query("SELECT * FROM message_snapshots WHERE conversationId = :conversationId ORDER BY id DESC")
    fun observeForConversation(conversationId: Long): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM message_snapshots WHERE id = :id")
    suspend fun getById(id: Long): SnapshotEntity?

    @Query("DELETE FROM message_snapshots WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM message_snapshots")
    suspend fun deleteAll()
}

@Dao
interface RunDao {

    @Insert
    suspend fun insert(entity: RunEntity): Long

    @Query(
        "UPDATE runs SET state = :state, roundsUsed = :rounds, toolCallsUsed = :toolCalls, " +
            "updatedAt = :now WHERE id = :id",
    )
    suspend fun finish(id: Long, state: String, rounds: Int, toolCalls: Int, now: Long)

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY id DESC LIMIT 1")
    suspend fun latestForConversation(conversationId: Long): RunEntity?

    @Query("SELECT * FROM runs WHERE id = :id")
    fun observeById(id: Long): Flow<RunEntity?>

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun getById(id: Long): RunEntity?

    @Query("DELETE FROM runs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE runs SET state = 'CANCELLED', updatedAt = :now WHERE state = 'RUNNING'")
    suspend fun cancelStale(now: Long)

    @Query("DELETE FROM runs")
    suspend fun deleteAll()

    @Query("DELETE FROM runs WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    @Query("SELECT COUNT(*) FROM runs")
    suspend fun countAll(): Int

    @Query("SELECT COALESCE(SUM(toolCallsUsed), 0) FROM runs")
    suspend fun totalToolCalls(): Int

    @Query(
        """
        SELECT r.id AS id, r.conversationId AS conversationId, c.title AS conversationTitle,
               r.mode AS mode, r.state AS state, r.startedAt AS startedAt, r.updatedAt AS updatedAt
        FROM runs r
        INNER JOIN conversations c ON c.id = r.conversationId
        WHERE r.mode IN ('ACT', 'MAX')
        ORDER BY r.id DESC
        LIMIT :limit
        """,
    )
    fun observeSummaries(limit: Int = 100): Flow<List<RunSummaryRow>>
}

data class RunSummaryRow(
    val id: Long,
    val conversationId: Long,
    val conversationTitle: String,
    val mode: String,
    val state: String,
    val startedAt: Long,
    val updatedAt: Long,
)

data class MessageSearchRow(
    val id: Long,
    val conversationId: Long,
    val conversationTitle: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Dao
interface McpServerDao {

    @Insert
    suspend fun insert(entity: McpServerEntity): Long

    @Query("SELECT * FROM mcp_servers ORDER BY id ASC")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY id ASC")
    suspend fun getAll(): List<McpServerEntity>

    @Query("UPDATE mcp_servers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AutomationDao {

    @Insert
    suspend fun insert(entity: AutomationEntity): Long

    @Query("SELECT * FROM automations ORDER BY id DESC")
    fun observeAll(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations ORDER BY id DESC")
    suspend fun getAll(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: Long): AutomationEntity?

    @Query("UPDATE automations SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PlanDao {

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlanEntity)

    @Query("SELECT * FROM plans WHERE conversationId = :conversationId")
    suspend fun get(conversationId: Long): PlanEntity?

    @Query("SELECT * FROM plans WHERE conversationId = :conversationId")
    fun observe(conversationId: Long): Flow<PlanEntity?>

    @Query("DELETE FROM plans")
    suspend fun deleteAll()
}

@Dao
interface NoteDao {

    @Insert
    suspend fun insert(entity: NoteEntity): Long

    @Query("SELECT * FROM notes WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    fun observeActive(limit: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    suspend fun getActive(limit: Int): List<NoteEntity>

    /**
     * Notes that existed at [at]: created before it, and either still active or
     * archived only after it. Suppressed traces stay forgotten.
     */
    @Query(
        "SELECT * FROM notes WHERE createdAt <= :at AND suppressed = 0 " +
            "AND (archived = 0 OR updatedAt > :at) " +
            "ORDER BY pinned DESC, updatedAt DESC LIMIT :limit",
    )
    suspend fun getAsOf(at: Long, limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE suppressed = 1 ORDER BY id DESC LIMIT :limit")
    suspend fun getSuppressed(limit: Int): List<NoteEntity>

    @Query(
        "SELECT * FROM notes WHERE archived = 0 AND entities LIKE :pattern ESCAPE '\\' " +
            "ORDER BY pinned DESC, updatedAt DESC LIMIT :limit",
    )
    suspend fun getByEntity(pattern: String, limit: Int): List<NoteEntity>

    @Query("SELECT entities FROM notes WHERE archived = 0 AND entities != ''")
    suspend fun activeEntityBlobs(): List<String>

    @Query("SELECT * FROM notes WHERE archived = 0 AND content = :content LIMIT 1")
    suspend fun findByContent(content: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    @Query(
        "UPDATE notes SET content = :content, importance = :importance, " +
            "expiresAt = COALESCE(:expiresAt, expiresAt), updatedAt = :now WHERE id = :id",
    )
    suspend fun update(id: Long, content: String, importance: Int, now: Long, expiresAt: Long? = null)

    @Query(
        "UPDATE notes SET content = :content, importance = :importance, entities = :entities, " +
            "expiresAt = COALESCE(:expiresAt, expiresAt), updatedAt = :now WHERE id = :id",
    )
    suspend fun updateWithEntities(
        id: Long,
        content: String,
        importance: Int,
        entities: String,
        now: Long,
        expiresAt: Long? = null,
    )

    @Query(
        "UPDATE notes SET kind = :kind, content = :content, importance = :importance, " +
            "entities = :entities, expiresAt = COALESCE(:expiresAt, expiresAt), " +
            "messageId = COALESCE(:messageId, messageId), " +
            "archived = 0, suppressed = 0, supersededBy = NULL, " +
            "updatedAt = :now WHERE id = :id",
    )
    suspend fun replaceInPlace(
        id: Long,
        kind: String,
        content: String,
        importance: Int,
        entities: String,
        now: Long,
        expiresAt: Long? = null,
        messageId: Long? = null,
    )

    @Query("UPDATE notes SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("UPDATE notes SET archived = 1, supersededBy = :by, updatedAt = :now WHERE id = :id")
    suspend fun archive(id: Long, by: Long?, now: Long)

    /** The user asked to forget: archive and remember the suppression. */
    @Query("UPDATE notes SET archived = 1, suppressed = 1, updatedAt = :now WHERE id = :id")
    suspend fun suppress(id: Long, now: Long)

    @Query(
        "UPDATE notes SET lastAccessedAt = :now, accessCount = accessCount + 1, " +
            "strength = MIN(strength + 0.6, 5.0) WHERE id = :id",
    )
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM notes WHERE archived = 0")
    suspend fun countActive(): Int
}

@Dao
interface NoteRevisionDao {

    @Insert
    suspend fun insert(entity: NoteRevisionEntity): Long

    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY id DESC LIMIT :limit")
    suspend fun revisionsFor(noteId: Long, limit: Int = 10): List<NoteRevisionEntity>

    /** The before-image saved by the first rewrite after [at]; null if none. */
    @Query(
        "SELECT * FROM note_revisions WHERE noteId = :noteId AND replacedAt > :at " +
            "ORDER BY replacedAt ASC LIMIT 1",
    )
    suspend fun revisionAsOf(noteId: Long, at: Long): NoteRevisionEntity?

    @Query(
        "DELETE FROM note_revisions WHERE noteId = :noteId AND id NOT IN " +
            "(SELECT id FROM note_revisions WHERE noteId = :noteId ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun prune(noteId: Long, keep: Int)

    @Query("DELETE FROM note_revisions WHERE noteId = :noteId")
    suspend fun deleteFor(noteId: Long)
}

@Dao
interface MemoryHoldDao {

    @Insert
    suspend fun insert(entity: MemoryHoldEntity): Long

    @Query("SELECT * FROM memory_holds ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<MemoryHoldEntity>

    @Query("SELECT * FROM memory_holds ORDER BY id DESC LIMIT 200")
    fun observeAll(): Flow<List<MemoryHoldEntity>>

    @Query("DELETE FROM memory_holds WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CuratorRunDao {

    @Insert
    suspend fun insert(entity: CuratorRunEntity): Long

    @Query("SELECT * FROM curator_runs ORDER BY ranAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CuratorRunEntity>

    @Query("SELECT * FROM curator_runs ORDER BY ranAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CuratorRunEntity>>

    @Query("SELECT MAX(ranAt) FROM curator_runs")
    suspend fun latestRanAt(): Long?
}

@Dao
interface NoteLinkDao {

    @Insert
    suspend fun insert(entity: NoteLinkEntity)

    @Query("SELECT * FROM note_links WHERE a = :a AND b = :b")
    suspend fun get(a: Long, b: Long): NoteLinkEntity?

    @Query("UPDATE note_links SET weight = :weight, updatedAt = :now WHERE a = :a AND b = :b")
    suspend fun updateWeight(a: Long, b: Long, weight: Float, now: Long)

    @Query("SELECT * FROM note_links WHERE a IN (:ids) OR b IN (:ids)")
    suspend fun linksFor(ids: List<Long>): List<NoteLinkEntity>

    @Query("DELETE FROM note_links WHERE a = :id OR b = :id")
    suspend fun deleteFor(id: Long)
}

@Dao
interface CoreMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CoreMemoryEntity)

    @Query("SELECT * FROM core_memory WHERE slot = :slot")
    suspend fun get(slot: String): CoreMemoryEntity?

    @Query("SELECT * FROM core_memory")
    fun observeAll(): Flow<List<CoreMemoryEntity>>

    @Query("SELECT * FROM core_memory")
    suspend fun getAll(): List<CoreMemoryEntity>
}
