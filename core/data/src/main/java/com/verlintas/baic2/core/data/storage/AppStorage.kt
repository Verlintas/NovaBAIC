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

package com.verlintas.baic2.core.data.storage

import android.content.Context
import androidx.room.withTransaction
import com.verlintas.baic2.core.data.db.Baic2Database
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageUsage(
    val databaseBytes: Long,
    val attachmentsBytes: Long,
    val attachmentsCount: Int,
    val screenshotsBytes: Long,
    val screenshotsCount: Int,
    val skillsBytes: Long,
    val totalBytes: Long,
)

/** Local storage facts and cleanup actions for the settings screen. */
@Singleton
class AppStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: Baic2Database,
) {

    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val attachments = File(context.filesDir, "attachments")
        val screenshots = File(context.filesDir, "screenshots")
        val skills = File(context.filesDir, "skills")
        val dbFile = context.getDatabasePath("baic2.db")
        StorageUsage(
            databaseBytes = dbFile.length(),
            attachmentsBytes = attachments.directorySize(),
            attachmentsCount = attachments.countFiles(),
            screenshotsBytes = screenshots.directorySize(),
            screenshotsCount = screenshots.countFiles(),
            skillsBytes = skills.directorySize(),
            totalBytes = dbFile.length() + attachments.directorySize() +
                screenshots.directorySize() + skills.directorySize(),
        )
    }

    suspend fun clearAttachments() = withContext(Dispatchers.IO) {
        File(context.filesDir, "attachments").deleteRecursively()
    }

    suspend fun clearScreenshots() = withContext(Dispatchers.IO) {
        File(context.filesDir, "screenshots").deleteRecursively()
    }

    /**
     * Wipes conversation data only: agents and API keys, memory (notes,
     * revisions, holds), automations, MCP servers and settings all survive.
     */
    suspend fun clearConversations() = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.messageDao().deleteAll()
            db.snapshotDao().deleteAll()
            db.planDao().deleteAll()
            db.runDao().deleteAll()
            db.scheduledTaskDao().clearAllConversations()
            db.conversationDao().deleteAll()
        }
    }

    private fun File.directorySize(): Long =
        if (!exists()) 0L else walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun File.countFiles(): Int =
        if (!exists()) 0 else walkTopDown().count { it.isFile }
}
