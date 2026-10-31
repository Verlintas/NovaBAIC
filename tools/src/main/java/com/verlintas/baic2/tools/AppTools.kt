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

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.tools.apps.AppResolver
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

class OpenAppTool : DeviceTool {

    override val spec = ToolSpec(
        name = "open_app",
        description = "Launch an installed app by display name or package. Fuzzy matching: " +
            "'微信', 'settings', 'chrome' and exact packages all work without listing apps first.",
        parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"app label (partial ok) or package name"},"package":{"type":"string"}},"required":[]}""",
        readOnly = false,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = (arguments["package"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
            ?: (arguments["name"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("Provide 'name' (app label) or 'package'")

        val matches = AppResolver.resolve(context.appContext, query, launchableOnly = true)
        val best = matches.firstOrNull()
            ?: return ToolResult.Failure(
                "No launchable app matches '$query'. Use list_installed_apps to see what is installed.",
            )

        val launchIntent = AppResolver.launchIntent(context.appContext, best.packageName)
            ?: return ToolResult.Failure("'${best.label}' (${best.packageName}) has no launchable activity.")
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.appContext.startActivity(launchIntent)
            delay(700)
            val foreground = context.accessibility.foregroundPackage()
            val alternatives = matches.drop(1).take(3)
            ToolResult.Success(
                buildString {
                    append("Launched \"").append(best.label).append("\" (").append(best.packageName).append(')')
                    if (foreground == best.packageName) append(" — now in the foreground")
                    if (alternatives.isNotEmpty()) {
                        append(". Other matches: ").append(
                            alternatives.joinToString("; ") { "${it.label} (${it.packageName})" },
                        )
                    }
                },
            )
        } catch (e: Exception) {
            ToolResult.Failure("Could not launch ${best.packageName}: ${e.message}")
        }
    }
}

class OpenSettingsTool : DeviceTool {
    override val spec = ToolSpec(
        name = "open_settings",
        description = "Open a system settings page. Pages: wifi, bluetooth, apps, display, sound, battery, " +
            "accessibility, notification, location, home, settings.",
        parametersJson = """{"type":"object","properties":{"page":{"type":"string","description":"Page key"}},"required":["page"]}""",
        readOnly = false,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val page = (arguments["page"] as? JsonPrimitive)?.content?.lowercase()
            ?: return ToolResult.Failure("Missing 'page' argument")
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notification" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "home", "settings" -> Settings.ACTION_SETTINGS
            else -> return ToolResult.Failure(
                "Unknown page '$page'. Use one of: wifi, bluetooth, apps, display, sound, battery, " +
                    "accessibility, notification, location, settings.",
            )
        }
        return try {
            context.appContext.startActivity(
                Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ToolResult.Success("Opened settings page: $page")
        } catch (e: Exception) {
            ToolResult.Failure("Could not open '$page': ${e.message}")
        }
    }
}

class ListInstalledAppsTool : DeviceTool {
    override val spec = ToolSpec(
        name = "list_installed_apps",
        description = "List installed apps (label + package). Filter by partial label, optionally include system apps.",
        parametersJson = """{"type":"object","properties":{"filter":{"type":"string"},"include_system":{"type":"boolean"},"limit":{"type":"integer","description":"Max rows, default 60"}}}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val filter = (arguments["filter"] as? JsonPrimitive)?.content?.lowercase()
        val includeSystem = (arguments["include_system"] as? JsonPrimitive)?.booleanOrNull ?: false
        val limit = ((arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 60).coerceIn(1, 200)

        val pm = context.appContext.packageManager
        val apps = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
            .asSequence()
            .filter { includeSystem || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { app -> pm.getApplicationLabel(app).toString() to app.packageName }
            .filter { (label, pkg) ->
                filter.isNullOrBlank() ||
                    label.lowercase().contains(filter) ||
                    pkg.lowercase().contains(filter)
            }
            .sortedBy { (label, _) -> label.lowercase() }
            .take(limit)
            .toList()

        if (apps.isEmpty()) return ToolResult.Success("No matching apps.")
        return ToolResult.Success(
            apps.joinToString("\n") { (label, pkg) -> "$label — $pkg" },
        )
    }
}
