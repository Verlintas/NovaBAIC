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
import kotlinx.serialization.json.Json

/** Portable snapshot of everything worth keeping: notes, core memory, holds. */
@Serializable
data class MemoryBackup(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val core: MemoryBackupCore? = null,
    val notes: List<MemoryBackupNote> = emptyList(),
    val holds: List<MemoryBackupHold> = emptyList(),
)

@Serializable
data class MemoryBackupCore(
    val user: String = "",
    val context: String = "",
)

@Serializable
data class MemoryBackupNote(
    val content: String,
    val kind: String = "fact",
    val importance: Int = 3,
    val source: String = "user",
    val entities: List<String> = emptyList(),
    val whenAt: Long? = null,
    val expiresAt: Long? = null,
    val createdAt: Long? = null,
)

@Serializable
data class MemoryBackupHold(
    val content: String,
    val reason: String? = null,
)

object MemoryBackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: MemoryBackup): String =
        json.encodeToString(MemoryBackup.serializer(), backup)

    fun decode(text: String): Result<MemoryBackup> =
        runCatching { json.decodeFromString(MemoryBackup.serializer(), text) }
}
