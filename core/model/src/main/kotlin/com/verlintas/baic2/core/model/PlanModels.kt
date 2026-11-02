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
enum class PlanStepStatus {
    PENDING,
    DOING,
    DONE,
    FAILED,
}

@Serializable
data class PlanStep(
    val title: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
)

/** The model-maintained task plan for a conversation (a first-class artifact). */
@Serializable
data class Plan(
    val conversationId: Long,
    val steps: List<PlanStep>,
    val updatedAt: Long,
) {
    val doneCount: Int get() = steps.count { it.status == PlanStepStatus.DONE }

    fun render(): String = buildString {
        append("Progress: $doneCount/${steps.size}")
        steps.forEachIndexed { index, step ->
            val marker = when (step.status) {
                PlanStepStatus.PENDING -> "[ ]"
                PlanStepStatus.DOING -> "[>]"
                PlanStepStatus.DONE -> "[x]"
                PlanStepStatus.FAILED -> "[!]"
            }
            append('\n').append(marker).append(' ').append(index + 1).append(". ").append(step.title)
        }
    }
}

data class RunSummary(
    val id: Long,
    val conversationId: Long,
    val conversationTitle: String,
    val mode: String,
    val state: RunState,
    val startedAt: Long,
    val updatedAt: Long,
)
