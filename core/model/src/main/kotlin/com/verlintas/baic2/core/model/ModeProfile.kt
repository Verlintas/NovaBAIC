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

import java.util.UUID
import kotlinx.serialization.Serializable

/** Which tools a mode may reach. */
@Serializable
enum class ToolAccess { NONE, READ_ONLY, FULL }

/**
 * A chat mode: what the agent may touch, whether every call is confirmed, and
 * how far one run may go. Stock modes are profiles with fixed ids; user modes
 * use the same shape. The mode list is user-managed: every entry, stock or
 * custom, can be edited or removed.
 */
@Serializable
data class ModeProfile(
    /** Stable key stored on conversations and scheduled tasks. */
    val id: String,
    /** Display name; null on stock profiles, whose labels are localized. */
    val name: String? = null,
    val toolAccess: ToolAccess,
    val requiresConfirmation: Boolean,
    val budget: RunBudget,
    val stock: Boolean = false,
    /**
     * Long-task behavior: planning protocol, completion report and the
     * finish banner. Null on rows saved before this existed; those fall
     * back to the old rule (only the stock MAX class behaved that way).
     */
    val taskMode: Boolean? = null,
    /** Extra instructions appended to the system prompt of this mode. */
    val prompt: String? = null,
    /**
     * Full consent: tainted HIGH-danger calls run without asking instead of
     * being downgraded to confirmation. The mode's own per-call confirmation
     * still applies; unattended runs still refuse them outright.
     */
    val fullConsent: Boolean = false,
) {
    /** No tools at all besides memory (old CHAT semantics). */
    val toolsVisible: Boolean get() = toolAccess != ToolAccess.NONE

    /** Read-only tools only (old CHAT_PLUS semantics). */
    val readOnlyOnly: Boolean get() = toolAccess == ToolAccess.READ_ONLY

    /** Full tools without a per-call gate (old MAX semantics). */
    val autonomous: Boolean get() = toolAccess == ToolAccess.FULL && !requiresConfirmation

    /** Work worth recording in Tasks: the mode can act on the device. */
    val agentic: Boolean get() = toolAccess == ToolAccess.FULL

    /** Plan protocol, completion report and the finish banner are on. */
    val isTask: Boolean get() = taskMode ?: (stock && autonomous)

    /** Agent persona plus this mode's own extra instructions. */
    fun composeCustomPrompt(agentPrompt: String): String = when {
        prompt.isNullOrBlank() -> agentPrompt
        agentPrompt.isBlank() -> prompt.orEmpty()
        else -> agentPrompt + "\n\n" + prompt
    }

    /** Long-horizon work keeps a visible plan (old MAX / Research). */
    val planEnabled: Boolean get() = readOnlyOnly || isTask

    /**
     * The engine behavior class. The engine gate, tool registry and prompt
     * contracts are keyed on these four; a profile only adds its own name and
     * budget on top. Confirmation is meaningful only with full tool access,
     * so the mapping stays exact.
     */
    val engineMode: AppMode
        get() = when {
            toolAccess == ToolAccess.NONE -> AppMode.CHAT
            toolAccess == ToolAccess.READ_ONLY -> AppMode.CHAT_PLUS
            requiresConfirmation -> AppMode.ACT
            else -> AppMode.MAX
        }

    companion object {
        /** Id for a user-created mode. */
        fun newId(): String = "custom-" + UUID.randomUUID()
    }
}

/** The stock mode profiles; also the default mode list. */
object ModeProfiles {
    val CHAT = ModeProfile(
        id = "CHAT",
        toolAccess = ToolAccess.NONE,
        requiresConfirmation = false,
        budget = RunBudget(maxRounds = 4, maxToolCalls = 6, maxWallClockMs = 300_000),
        stock = true,
    )
    val CHAT_PLUS = ModeProfile(
        id = "CHAT_PLUS",
        toolAccess = ToolAccess.READ_ONLY,
        requiresConfirmation = false,
        budget = RunBudget(maxRounds = 8, maxToolCalls = 25, maxWallClockMs = 600_000),
        stock = true,
    )
    val ACT = ModeProfile(
        id = "ACT",
        toolAccess = ToolAccess.FULL,
        requiresConfirmation = true,
        budget = RunBudget(maxRounds = 12, maxToolCalls = 32, maxWallClockMs = 600_000),
        stock = true,
    )
    val MAX = ModeProfile(
        id = "MAX",
        toolAccess = ToolAccess.FULL,
        requiresConfirmation = false,
        budget = RunBudget(maxRounds = 60, maxToolCalls = 160, maxWallClockMs = 3_600_000),
        stock = true,
        taskMode = true,
    )

    val stocks: List<ModeProfile> = listOf(CHAT, CHAT_PLUS, ACT, MAX)

    /** Shape for an id that is no longer in the mode list. */
    fun fallback(id: String): ModeProfile = CHAT.copy(id = id, name = null, stock = false)
}

/** Stored profile for an id; unknown ids degrade to plain conversation. */
fun List<ModeProfile>.resolve(id: String): ModeProfile =
    firstOrNull { it.id == id } ?: ModeProfiles.fallback(id)

