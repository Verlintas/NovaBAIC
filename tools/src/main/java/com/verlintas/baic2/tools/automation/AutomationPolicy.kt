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

package com.verlintas.baic2.tools.automation

import com.verlintas.baic2.core.model.DangerLevel

/**
 * One policy for unattended automation actions, enforced both at creation and
 * again at execution time (tools can be shadowed or re-registered in between).
 */
internal object AutomationPolicy {

    val FORBIDDEN = setOf("automation", "spawn_agent", "plan_update")

    /** null = allowed; otherwise the reason to refuse. */
    fun denialReason(toolName: String, danger: DangerLevel?): String? = when {
        danger == null -> "unknown tool"
        toolName in FORBIDDEN -> "tool '$toolName' cannot run inside an automation"
        danger == DangerLevel.HIGH -> "tool '$toolName' is too dangerous to run unattended"
        else -> null
    }
}
