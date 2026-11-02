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

import java.util.Calendar

/**
 * A user-scheduled agent prompt: "run this in MAX mode every weekday at 08:00".
 * The alarm fires a background run that writes into [conversationId] (created
 * on first run) and reports the result in a notification.
 */
data class ScheduledTask(
    val id: Long = 0L,
    val name: String,
    val prompt: String,
    /** Mode profile id (ModeProfile.id); stock ids match legacy rows. */
    val mode: String = "MAX",
    val agentId: Long? = null,
    val timeOfDay: String = "08:00",
    /** ISO weekdays 1..7; empty means every day. */
    val daysOfWeek: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val conversationId: Long? = null,
    val lastRunAt: Long = 0L,
    val nextRunAt: Long = 0L,
    val lastResult: String? = null,
    val createdAt: Long = 0L,
)

/**
 * Next wall-clock instant matching [timeOfDay] ("HH:mm") and [daysOfWeek]
 * (ISO 1..7, empty = daily) after [from]. Null when the spec is malformed.
 */
fun nextScheduledTrigger(timeOfDay: String, daysOfWeek: Set<Int>, from: Long): Long? {
    val parts = timeOfDay.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    for (offset in 0..7) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = from
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= from) continue
        if (daysOfWeek.isEmpty()) return calendar.timeInMillis
        val isoDay = calendar.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
        if (isoDay in daysOfWeek) return calendar.timeInMillis
    }
    return null
}
