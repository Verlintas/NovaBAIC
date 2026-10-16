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

@Serializable
enum class AutomationTrigger {
    TIME,
    BATTERY,
    /** Fires when a notification arrives from the given packages/keyword. */
    NOTIFICATION,
}

@Serializable
data class AutomationAction(
    val tool: String,
    val argsJson: String = "{}",
)

@Serializable
data class Automation(
    val id: Long = 0L,
    val name: String,
    val trigger: AutomationTrigger,
    /** "HH:mm" for TIME triggers. */
    val timeOfDay: String? = null,
    /** ISO weekdays 1..7 (Mon..Sun); null means every day. */
    val daysOfWeek: List<Int>? = null,
    /** Fire when battery drops below this percentage (BATTERY trigger). */
    val batteryBelow: Int? = null,
    /** Package filters for NOTIFICATION triggers (empty = any package). */
    val notificationPackages: List<String> = emptyList(),
    /** Keyword filter on title/text for NOTIFICATION triggers. */
    val notificationKeyword: String? = null,
    val actions: List<AutomationAction>,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
) {
    fun scheduleLabel(): String = when (trigger) {
        AutomationTrigger.TIME -> {
            val days = daysOfWeek?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.toString() } ?: "daily"
            "at ${timeOfDay ?: "?"} ($days)"
        }

        AutomationTrigger.BATTERY -> "battery < ${batteryBelow ?: 20}%"

        AutomationTrigger.NOTIFICATION -> buildString {
            append("notification from ")
            append(notificationPackages.joinToString(", ").ifBlank { "any package" })
            notificationKeyword?.takeIf { it.isNotBlank() }?.let { append(" matching \"").append(it).append('"') }
        }
    }
}
