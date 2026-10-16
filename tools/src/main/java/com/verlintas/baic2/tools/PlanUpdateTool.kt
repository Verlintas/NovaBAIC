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

package com.verlintas.baic2.tools

import com.verlintas.baic2.core.data.repository.PlanRepository
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.PlanStep
import com.verlintas.baic2.core.model.PlanStepStatus
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Creates or updates the run plan. The model passes the full step list each
 * time; statuses are the source of truth for the user-facing plan card.
 */
class PlanUpdateTool(
    private val planRepository: PlanRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "plan_update",
        description = "Create or update the task plan. Pass the FULL step list with each step's status " +
            "(pending/doing/done/failed). Call it when starting a task and after every verification.",
        parametersJson = """{"type":"object","properties":{"steps":{"type":"array","items":{"type":"object","properties":{"title":{"type":"string"},"status":{"type":"string","enum":["pending","doing","done","failed"]}},"required":["title"]}}},"required":["steps"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val conversationId = context.run.conversationId
            ?: return ToolResult.Failure("No active conversation for this plan.")
        val rawSteps = arguments["steps"] as? JsonArray
            ?: return ToolResult.Failure("Missing 'steps' array")
        val steps = rawSteps.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val status = when (obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "doing", "in_progress" -> PlanStepStatus.DOING
                "done", "completed" -> PlanStepStatus.DONE
                "failed" -> PlanStepStatus.FAILED
                else -> PlanStepStatus.PENDING
            }
            PlanStep(title = title, status = status)
        }
        if (steps.isEmpty()) {
            return ToolResult.Failure("Plan must contain at least one step with a title.")
        }
        planRepository.savePlan(conversationId, steps)
        val done = steps.count { it.status == PlanStepStatus.DONE }
        return ToolResult.Success("Plan updated: $done/${steps.size} steps done.")
    }
}
