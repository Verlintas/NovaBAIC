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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a run's tool calls into short, human-readable impact lines ("tapped
 * X", "wrote report.md", "ran `pm clear …`") so the Tasks detail answers
 * "what did it actually do" at a glance instead of requiring a transcript
 * read. Read-only/no-op tools are skipped.
 */
object ImpactSummarizer {

    fun summarize(messages: List<ChatMessage>, limit: Int = 12): List<String> {
        val lines = mutableListOf<String>()
        messages.forEach { message ->
            if (message.role != ChatRole.ASSISTANT) return@forEach
            message.toolCalls.forEach { call ->
                describe(call)?.let { line ->
                    if (lines.lastOrNull() != line && lines.size < limit) lines += line
                }
            }
        }
        return lines
    }

    private fun describe(call: ToolCall): String? {
        val args = parseArgs(call.argumentsJson)
        fun str(key: String): String? =
            (args?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        val line = when (call.name) {
            "open_app" -> "打开应用：${str("name") ?: str("package") ?: "?"}"
            "ui_control" -> when (str("action")) {
                "tap", "long_press" -> "点击：${str("text") ?: "坐标"}"
                "type" -> "输入文本：${(str("value") ?: "").take(24)}"
                "press_key" -> "按键：${str("value") ?: "?"}"
                "scroll" -> "滚动：${str("direction") ?: "?"}"
                else -> "屏幕操作：${str("action") ?: "?"}"
            }
            "web_search" -> "网络搜索：${queryText(args).take(40)}"
            "web_read" -> "阅读网页：${(str("url") ?: "").take(60)}"
            "app_research" -> "App 内检索：${str("app") ?: "?"} · ${str("query") ?: "?"}"
            "file_write" -> when (str("action")) {
                "append" -> "追加文件：${str("name") ?: "?"}"
                "delete" -> "删除文件：${str("name") ?: "?"}"
                else -> "写入文件：${str("name") ?: "?"}"
            }
            "files" -> "读取文件：${str("name") ?: "?"}"
            "download_file" -> "下载文件：${str("file_name") ?: (str("url") ?: "").takeLast(30)}"
            "run_shell" -> "执行命令：${(str("command") ?: "").take(60)}"
            "manage_app" -> "应用管理：${str("action") ?: "?"} ${str("app") ?: ""}".trim()
            "take_screenshot" -> "截屏"
            "screen_ocr" -> "读取屏幕"
            "screen_record" -> "录屏 ${str("seconds") ?: "?"}s"
            "transcribe_audio" -> "语音转写 ${str("seconds") ?: "?"}s"
            "set_volume" -> "调整音量：${str("percent") ?: str("level") ?: "?"}"
            "set_brightness" -> "调整亮度：${str("percent") ?: str("level") ?: "?"}"
            "set_flashlight" -> "手电筒：${str("state") ?: str("on") ?: "?"}"
            "set_clipboard" -> "写入剪贴板"
            "share_text" -> "分享文本"
            "send_notification" -> "发送通知：${str("title") ?: ""}".trim()
            "send_email" -> "发送邮件：${str("to") ?: ""}".trimEnd('：')
            "create_calendar_event" -> "创建日程：${str("title") ?: "?"}"
            "reminder" -> "设置提醒：${(str("message") ?: str("title") ?: "?").take(30)}"
            "spawn_agent" -> "派生子代理：${(str("task") ?: "").take(30)}"
            "automation" -> "自动化：${str("name") ?: str("action") ?: "?"}"
            "media_control" -> "媒体控制：${str("action") ?: "?"}"
            "get_weather" -> "查询天气：${str("city") ?: "?"}"
            "search_contacts" -> "搜索联系人：${str("query") ?: "?"}"
            "read_notifications" -> "读取通知"
            "get_location" -> "获取位置"
            "list_installed_apps", "device_info", "network_status",
            "get_screen_state", "get_foreground_app", "compute", "plan_update",
            "load_skill", "get_clipboard", "get_app_usage", "vibrate",
            -> null
            else -> "调用工具：${call.name}"
        }
        return line?.take(90)
    }

    private fun queryText(args: JsonObject?): String {
        val query = args?.get("query") ?: return "?"
        return when (query) {
            is JsonPrimitive -> query.content
            is JsonArray -> query.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" | ")
            else -> "?"
        }
    }

    private fun parseArgs(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}
