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

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AgentEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        RunEntity::class,
        NoteEntity::class,
        NoteLinkEntity::class,
        NoteRevisionEntity::class,
        MemoryHoldEntity::class,
        CuratorRunEntity::class,
        CoreMemoryEntity::class,
        PlanEntity::class,
        AutomationEntity::class,
        McpServerEntity::class,
        SnapshotEntity::class,
        ScheduledTaskEntity::class,
    ],
    version = 19,
    exportSchema = true,
)
abstract class Baic2Database : RoomDatabase() {

    abstract fun agentDao(): AgentDao

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun runDao(): RunDao

    abstract fun noteDao(): NoteDao

    abstract fun noteLinkDao(): NoteLinkDao

    abstract fun noteRevisionDao(): NoteRevisionDao

    abstract fun memoryHoldDao(): MemoryHoldDao

    abstract fun curatorRunDao(): CuratorRunDao

    abstract fun coreMemoryDao(): CoreMemoryDao

    abstract fun planDao(): PlanDao

    abstract fun automationDao(): AutomationDao

    abstract fun mcpServerDao(): McpServerDao

    abstract fun snapshotDao(): SnapshotDao

    abstract fun scheduledTaskDao(): ScheduledTaskDao

    companion object {
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Consolidation becomes observable: what ran, what it changed,
                // and whether the plan parsed - empty is not failure.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS curator_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ranAt INTEGER NOT NULL,
                        trigger TEXT NOT NULL,
                        conversationId INTEGER,
                        messages INTEGER NOT NULL,
                        windowFrom INTEGER NOT NULL,
                        windowTo INTEGER NOT NULL,
                        notesScanned INTEGER NOT NULL,
                        added INTEGER NOT NULL,
                        revised INTEGER NOT NULL,
                        forgotten INTEGER NOT NULL,
                        rehearsed INTEGER NOT NULL,
                        parsed INTEGER NOT NULL,
                        error TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_curator_runs_ranAt ON curator_runs(ranAt)",
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Perishable facts (locations, "currently…") carry a horizon.
                db.execSQL("ALTER TABLE notes ADD COLUMN expiresAt INTEGER")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Notes keep their own history; promises to not record become state.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_revisions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        noteId INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        importance INTEGER NOT NULL,
                        entities TEXT NOT NULL,
                        replacedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_note_revisions_noteId " +
                        "ON note_revisions(noteId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_holds (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content TEXT NOT NULL,
                        reason TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Source monitoring + suppression + entity index.
                db.execSQL("ALTER TABLE notes ADD COLUMN source TEXT NOT NULL DEFAULT 'USER'")
                db.execSQL("ALTER TABLE notes ADD COLUMN entities TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN suppressed INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Synaptic strength: retrieval makes a trace decay more slowly.
                db.execSQL("ALTER TABLE notes ADD COLUMN strength REAL NOT NULL DEFAULT 1.0")
                // Hebbian associations between notes recalled together.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_links (
                        a INTEGER NOT NULL,
                        b INTEGER NOT NULL,
                        weight REAL NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(a, b)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_links_b ON note_links(b)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        kind TEXT NOT NULL,
                        content TEXT NOT NULL,
                        importance INTEGER NOT NULL,
                        pinned INTEGER NOT NULL,
                        conversationId INTEGER,
                        messageId INTEGER,
                        whenAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastAccessedAt INTEGER NOT NULL,
                        accessCount INTEGER NOT NULL,
                        supersededBy INTEGER,
                        archived INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_kind ON notes(kind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes(updatedAt)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS core_memory (
                        slot TEXT NOT NULL,
                        content TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(slot)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_createdAt ON messages(createdAt)")
                // The old distilled facts and compression snapshots become notes;
                // provenance (conversationId) is preserved, nothing is lost.
                db.execSQL(
                    """
                    INSERT INTO notes (
                        kind, content, importance, pinned, conversationId, messageId, whenAt,
                        createdAt, updatedAt, lastAccessedAt, accessCount, supersededBy, archived
                    )
                    SELECT 'FACT', content, 3, 0, conversationId, NULL, NULL,
                           createdAt, createdAt, 0, 0, NULL, 0
                    FROM memories WHERE kind = 'MEMORY'
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO notes (
                        kind, content, importance, pinned, conversationId, messageId, whenAt,
                        createdAt, updatedAt, lastAccessedAt, accessCount, supersededBy, archived
                    )
                    SELECT 'SUMMARY', content, 3, 0, conversationId, NULL, NULL,
                           createdAt, createdAt, 0, 0, NULL, 0
                    FROM memories WHERE kind = 'SNAPSHOT'
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE memories")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_starred ON messages(starred)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        kind TEXT NOT NULL,
                        content TEXT NOT NULL,
                        conversationId INTEGER,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_kind ON memories(kind)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN thinkingMs INTEGER")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN thinkingSignature TEXT")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        agentId INTEGER,
                        timeOfDay TEXT NOT NULL,
                        daysOfWeekJson TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        conversationId INTEGER,
                        lastRunAt INTEGER NOT NULL,
                        nextRunAt INTEGER NOT NULL,
                        lastResult TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN roundsUsed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN toolCallsUsed INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Chat / Chat+ turns used to create run rows; Tasks is agentic
                // only, so remove the bookkeeping rows those turns left behind.
                db.execSQL("DELETE FROM runs WHERE mode NOT IN ('ACT', 'MAX')")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        carrierId INTEGER NOT NULL,
                        keepFromMessageId INTEGER NOT NULL,
                        payloadJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_snapshots_conversationId " +
                        "ON message_snapshots(conversationId)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN usageInput INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN usageOutput INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mcp_servers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        url TEXT NOT NULL,
                        headersJson TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS automations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        trigger TEXT NOT NULL,
                        timeOfDay TEXT,
                        daysOfWeekJson TEXT NOT NULL,
                        batteryBelow INTEGER,
                        actionsJson TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS plans (
                        conversationId INTEGER NOT NULL,
                        stepsJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(conversationId)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
