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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.verlintas.baic2.core.data.repository.AutomationRepository
import com.verlintas.baic2.core.engine.AgentLoop
import com.verlintas.baic2.core.engine.ToolRunner
import com.verlintas.baic2.core.model.Automation
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.tools.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Runs an automation's tool sequence and reports the outcome. */
@Singleton
class AutomationExecutor @Inject constructor(
    private val toolRunner: ToolRunner,
    private val automationRepository: AutomationRepository,
    private val registry: dagger.Lazy<com.verlintas.baic2.tools.ToolRegistry>,
    @ApplicationContext private val context: Context,
) {

    suspend fun run(automation: Automation): String {
        val lines = mutableListOf<String>()
        var failures = 0
        automation.actions.forEachIndexed { index, action ->
            val call = ToolCall(
                id = "automation_${automation.id}_$index",
                name = action.tool,
                argumentsJson = action.argsJson,
            )
            // Creation-time validation can go stale (dynamic skill tools can
            // shadow names later); re-check against the live registry.
            val denial = AutomationPolicy.denialReason(
                action.tool,
                registry.get().tool(action.tool)?.spec?.danger,
            )
            val result = if (denial != null) {
                ToolResult.Denied("$denial (refused at execution time)")
            } else try {
                toolRunner.run(
                    call,
                    com.verlintas.baic2.core.engine.ToolRunContext(
                        conversationId = null,
                        mode = com.verlintas.baic2.core.model.AppMode.MAX,
                        unattended = true,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.Failure(e.message ?: "crashed")
            }
            when (result) {
                is ToolResult.Success -> lines += "✓ ${action.tool}: ${result.output.take(120)}"
                is ToolResult.Failure -> {
                    failures++
                    lines += "✗ ${action.tool}: ${result.reason}"
                }
                is ToolResult.Denied -> {
                    failures++
                    lines += "⊘ ${action.tool}: ${result.reason}"
                }
            }
        }
        notify(automation, lines, failures)
        return lines.joinToString("\n")
    }

    private fun notify(automation: Automation, lines: List<String>, failures: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.automation_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val title = if (failures == 0) {
            context.getString(R.string.automation_done, automation.name)
        } else {
            context.getString(R.string.automation_partial, automation.name)
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(android.app.Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setAutoCancel(true)
            .build()
        manager.notify(automation.id.toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "baic2_automations"
    }
}
