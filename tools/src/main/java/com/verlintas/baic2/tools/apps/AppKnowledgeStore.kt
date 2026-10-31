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

package com.verlintas.baic2.tools.apps

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppKnowledgeCard(
    val packageName: String,
    val searchViewId: String? = null,
    val updatedAt: Long = 0,
    val successes: Int = 0,
)

interface AppKnowledgeRepository {
    suspend fun get(packageName: String): AppKnowledgeCard?

    /** Remember the structural id of the field that actually opened search. */
    suspend fun recordSearch(packageName: String, searchViewId: String?)
}

/**
 * Small, app-private knowledge cache. It stores a resource id, not a user
 * query or screen text, and is deliberately rebuildable.
 */
@Singleton
class AppKnowledgeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppKnowledgeRepository {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = ConcurrentHashMap<String, AppKnowledgeCard>()

    override suspend fun get(packageName: String): AppKnowledgeCard? = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()[packageName]
        }
    }

    override suspend fun recordSearch(
        packageName: String,
        searchViewId: String?,
    ) = withContext(Dispatchers.IO) {
        val id = searchViewId?.trim()?.takeIf { it.length in 1..200 } ?: return@withContext
        mutex.withLock {
            val now = System.currentTimeMillis()
            val previous = load()[packageName]
            val card = AppKnowledgeCard(
                packageName = packageName,
                searchViewId = id,
                updatedAt = now,
                successes = (previous?.successes ?: 0) + 1,
            )
            cache[packageName] = card
            save(cache.values.toList())
        }
    }

    private suspend fun load(): MutableMap<String, AppKnowledgeCard> {
        if (cache.isNotEmpty()) return cache
        val cards = runCatching {
            if (!file.isFile) emptyList() else {
                json.decodeFromString<List<AppKnowledgeCard>>(file.readText())
            }
        }.getOrDefault(emptyList())
        cards.forEach { cache[it.packageName] = it }
        return cache
    }

    private fun save(cards: List<AppKnowledgeCard>) {
        runCatching {
            file.writeText(json.encodeToString(cards.takeLast(MAX_CARDS)))
        }
    }

    private companion object {
        const val FILE_NAME = "app-knowledge.json"
        const val MAX_CARDS = 128
    }
}
