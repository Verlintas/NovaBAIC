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

import com.verlintas.baic2.core.data.repository.AutomationRepository
import com.verlintas.baic2.core.model.Automation
import com.verlintas.baic2.core.model.AutomationAction
import com.verlintas.baic2.core.model.AutomationTrigger
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import com.verlintas.baic2.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Create / list / delete / toggle automations. Actions are tool calls that
 * run unattended when the trigger fires.
 */
class AutomationTool(
    private val automationRepository: AutomationRepository,
    private val scheduler: AutomationScheduler,
    private val registry: dagger.Lazy<ToolRegistry>,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "automation",
        description = "Manage automations. action=create needs name + trigger(time|battery|notification) + actions " +
            "(array of {tool, args}); time uses \"HH:mm\" (+ optional days 1..7), battery uses battery_below, " +
            "notification uses packages (package names or substrings) and/or keyword and fires when such a " +
            "notification arrives (needs the notification-listener permission).",
        parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["create","list","delete","toggle"]},"name":{"type":"string"},"trigger":{"type":"string","enum":["time","battery","notification"]},"time":{"type":"string"},"days":{"type":"array","items":{"type":"integer"}},"battery_below":{"type":"integer"},"packages":{"type":"array","items":{"type":"string"},"description":"package names or substrings for notification triggers"},"keyword":{"type":"string","description":"title/text keyword for notification triggers"},"actions":{"type":"array","items":{"type":"object","properties":{"tool":{"type":"string"},"args":{"type":"object"}}}},"id":{"type":"integer"},"enabled":{"type":"boolean"}},"required":["action"]}""",
        readOnly = false,
        danger = DangerLevel.MEDIUM,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        return when (val action = (arguments["action"] as? JsonPrimitive)?.content?.lowercase()) {
            "create" -> create(arguments)
            "list" -> list()
            "delete" -> delete(arguments)
            "toggle" -> toggle(arguments)
            else -> ToolResult.Failure("Unknown action '$action'. Use create|list|delete|toggle.")
        }
    }

    private suspend fun create(arguments: JsonObject): ToolResult {
        val name = (arguments["name"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("Missing 'name'")
        val trigger = when ((arguments["trigger"] as? JsonPrimitive)?.content?.lowercase()) {
            "time" -> AutomationTrigger.TIME
            "battery" -> AutomationTrigger.BATTERY
            "notification" -> AutomationTrigger.NOTIFICATION
            else -> return ToolResult.Failure("Missing or invalid 'trigger' (time|battery|notification)")
        }
        val time = (arguments["time"] as? JsonPrimitive)?.content?.trim()
        val days = (arguments["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        val batteryBelow = (arguments["battery_below"] as? JsonPrimitive)?.intOrNull
        val packages = (arguments["packages"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            .filter { it.isNotEmpty() }
            .take(8)
        val keyword = (arguments["keyword"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

        when (trigger) {
            AutomationTrigger.TIME -> {
                val parts = time?.split(':')
                val hour = parts?.getOrNull(0)?.toIntOrNull()
                val minute = parts?.getOrNull(1)?.toIntOrNull()
                if (time == null || parts?.size != 2 || hour == null || minute == null ||
                    hour !in 0..23 || minute !in 0..59
                ) {
                    return ToolResult.Failure("time must be a valid \"HH:mm\" between 00:00 and 23:59")
                }
                if (days != null && days.any { it !in 1..7 }) {
                    return ToolResult.Failure("days must be 1..7 (Monday..Sunday)")
                }
            }

            AutomationTrigger.BATTERY -> {
                if (batteryBelow == null || batteryBelow !in 1..99) {
                    return ToolResult.Failure("battery_below must be 1-99")
                }
            }

            AutomationTrigger.NOTIFICATION -> {
                if (packages.isEmpty() && keyword == null) {
                    return ToolResult.Failure(
                        "notification triggers need 'packages' (e.g. [\"com.tencent.mm\"]) and/or 'keyword'",
                    )
                }
            }
        }

        val rawActions = arguments["actions"] as? JsonArray
            ?: return ToolResult.Failure("Missing 'actions' array")
        val actions = rawActions.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val tool = obj["tool"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val delegate = registry.get().tool(tool) ?: return ToolResult.Failure(
                "Unknown tool '$tool'. Available: ${registry.get().toolNames.joinToString()}",
            )
            AutomationPolicy.denialReason(tool, delegate.spec.danger)?.let { reason ->
                return ToolResult.Failure("$reason (automations run unattended).")
            }
            AutomationAction(
                tool = tool,
                argsJson = obj["args"]?.toString() ?: "{}",
            )
        }.let { it as List<AutomationAction> }
        if (actions.isEmpty()) return ToolResult.Failure("Provide at least one valid action")

        val automation = Automation(
            name = name,
            trigger = trigger,
            timeOfDay = time,
            daysOfWeek = days,
            batteryBelow = batteryBelow,
            notificationPackages = packages,
            notificationKeyword = keyword,
            actions = actions,
            enabled = true,
            createdAt = System.currentTimeMillis(),
        )
        val id = automationRepository.save(automation)
        automationRepository.getById(id)?.let { scheduler.schedule(it) }
        return ToolResult.Success(
            "Automation '$name' created (id=$id): ${automation.scheduleLabel()} " +
                "with ${actions.size} action(s).",
        )
    }

    private suspend fun list(): ToolResult {
        val automations = automationRepository.getAll()
        if (automations.isEmpty()) return ToolResult.Success("No automations yet.")
        return ToolResult.Success(
            buildString {
                append("Automations:\n")
                automations.forEach { automation ->
                    append("\n- [${automation.id}] ${automation.name} ")
                    append(if (automation.enabled) "(enabled)" else "(disabled)")
                    append(" — ${automation.scheduleLabel()}")
                    append("\n  actions: ")
                    append(automation.actions.joinToString(", ") { it.tool })
                }
            },
        )
    }

    private suspend fun delete(arguments: JsonObject): ToolResult {
        val id = (arguments["id"] as? JsonPrimitive)?.longOrNull
            ?: return ToolResult.Failure("Missing 'id'")
        val automation = automationRepository.getById(id)
            ?: return ToolResult.Failure("No automation with id=$id")
        scheduler.cancel(id)
        automationRepository.delete(id)
        return ToolResult.Success("Deleted automation '${automation.name}'")
    }

    private suspend fun toggle(arguments: JsonObject): ToolResult {
        val id = (arguments["id"] as? JsonPrimitive)?.longOrNull
            ?: return ToolResult.Failure("Missing 'id'")
        val enabled = (arguments["enabled"] as? JsonPrimitive)?.booleanOrNull
            ?: return ToolResult.Failure("Missing 'enabled' boolean")
        val automation = automationRepository.getById(id)
            ?: return ToolResult.Failure("No automation with id=$id")
        automationRepository.setEnabled(id, enabled)
        val updated = automation.copy(enabled = enabled)
        if (enabled) scheduler.schedule(updated) else scheduler.cancel(id)
        return ToolResult.Success("Automation '${automation.name}' ${if (enabled) "enabled" else "disabled"}")
    }

    private companion object {
    }
}
