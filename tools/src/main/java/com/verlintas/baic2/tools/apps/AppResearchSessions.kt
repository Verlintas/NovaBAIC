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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a virtual-display research page alive between model turns. A follow-up
 * with the returned session id can read the current page or open another
 * result without launching the app and re-running the search.
 */
@Singleton
class AppResearchSessions @Inject constructor() {

    data class Session(
        val id: String,
        val label: String,
        val packageName: String,
        val query: String,
        val displayId: Int,
        val updatedAt: Long,
        val lastText: String? = null,
        val opened: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun put(
        label: String,
        packageName: String,
        query: String,
        displayId: Int,
        lastText: String? = null,
        opened: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Session {
        prune(now)
        val session = Session(
            id = UUID.randomUUID().toString().take(8),
            label = label,
            packageName = packageName,
            query = query,
            displayId = displayId,
            updatedAt = now,
            lastText = lastText,
            opened = opened,
        )
        sessions[session.id] = session
        return session
    }

    fun get(id: String?, now: Long = System.currentTimeMillis()): Session? {
        val key = id?.trim().orEmpty()
        if (key.isEmpty()) return null
        val session = sessions[key] ?: return null
        if (now - session.updatedAt > TTL_MS) {
            sessions.remove(key)
            return null
        }
        return session
    }

    fun touch(id: String, now: Long = System.currentTimeMillis()) {
        sessions.computeIfPresent(id) { _, session -> session.copy(updatedAt = now) }
    }

    fun updateText(id: String, text: String?, now: Long = System.currentTimeMillis()) {
        sessions.computeIfPresent(id) { _, session ->
            session.copy(lastText = text, updatedAt = now)
        }
    }

    /** A reused display can be pointed at a new query without relaunching. */
    fun updateQueryAndText(
        id: String,
        query: String,
        text: String?,
        opened: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        sessions.computeIfPresent(id) { _, session ->
            session.copy(
                query = query,
                lastText = text,
                opened = opened,
                updatedAt = now,
            )
        }
    }

    fun remove(id: String) {
        sessions.remove(id)
    }

    fun clear() {
        sessions.clear()
    }

    private fun prune(now: Long) {
        sessions.entries.removeIf { now - it.value.updatedAt > TTL_MS }
        // A person can only be looking at one app at a time; keep the map tiny.
        if (sessions.size > MAX_SESSIONS) {
            sessions.entries
                .sortedBy { it.value.updatedAt }
                .take(sessions.size - MAX_SESSIONS)
                .forEach { sessions.remove(it.key) }
        }
    }

    private companion object {
        const val TTL_MS = 10 * 60_000L
        const val MAX_SESSIONS = 4
    }
}
