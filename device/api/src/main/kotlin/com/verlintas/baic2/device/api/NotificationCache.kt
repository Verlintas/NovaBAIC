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

package com.verlintas.baic2.device.api

import java.util.concurrent.CopyOnWriteArrayList

data class CachedNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

/** Recent notifications captured by the listener service while access is granted. */
object NotificationCache {
    private const val MAX = 60
    private val items = CopyOnWriteArrayList<CachedNotification>()

    /** Process-local listeners (notification automations); kept dependency-free. */
    private val listeners = CopyOnWriteArrayList<(CachedNotification) -> Unit>()

    fun addListener(listener: (CachedNotification) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (CachedNotification) -> Unit) {
        listeners.remove(listener)
    }

    fun record(notification: CachedNotification) {
        items.add(notification)
        while (items.size > MAX) items.removeAt(0)
        listeners.forEach { listener -> runCatching { listener(notification) } }
    }

    fun snapshot(limit: Int, sinceMillis: Long, appFilter: String?): List<CachedNotification> =
        items.asReversed()
            .filter { it.postedAt >= sinceMillis }
            .filter { appFilter.isNullOrBlank() || it.packageName.contains(appFilter, ignoreCase = true) }
            .take(limit.coerceIn(1, MAX))
}
