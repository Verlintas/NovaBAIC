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

package com.verlintas.baic2.core.engine

import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ToolSpec

/**
 * Keeps the research profile small. CHAT+ used to inherit every read-only
 * device tool; that is a lot of schema text for a mode that mostly reads,
 * remembers and searches. Core tools stay; the rest appear only when the
 * request actually mentions them.
 *
 * ACT/MAX keep the full catalog: they are execution modes and need the whole
 * toolbox. Their own prompt packs are the next step.
 */
object ToolRouter {

    private val CHAT_PLUS_CORE = setOf(
        "web_search",
        "web_read",
        "app_research",
        "spawn_agent",
        "screen_ocr",
        "plan_update",
        "ask_user",
        "compute",
    )

    /**
     * Ordered: earlier matches survive the cap. Keywords are matched against
     * the last user message, case-insensitively.
     */
    private val CHAT_PLUS_OPTIONAL = listOf(
        setOf("天气", "weather", "气温", "下雨") to setOf("get_weather"),
        setOf("rss", "订阅", "feed", "新闻源") to setOf("fetch_rss"),
        setOf("文件", "附件", "pdf", "文档", "file", "folder", "目录") to setOf("files"),
        setOf("联系人", "电话", "contact", "phone number") to setOf("search_contacts"),
        setOf("通知", "notification") to setOf("read_notifications"),
        setOf("用量", "使用时间", "usage", "screen time") to setOf("get_app_usage"),
        setOf("剪贴板", "clipboard", "复制") to setOf("get_clipboard"),
        setOf("设备", "电池", "系统版本", "device", "battery") to setOf("device_info"),
        setOf("网络", "wifi", "流量", "network", "connection") to setOf("network_status"),
        setOf("已安装", "应用列表", "installed app", "app list") to setOf("list_installed_apps"),
        setOf("截图", "截屏", "screenshot", "看图") to setOf("take_screenshot"),
        setOf("ocr", "图片", "image", "识别文字") to setOf("ocr_file"),
        setOf("二维码", "qr") to setOf("decode_qr"),
        setOf("音频", "录音", "转写", "audio", "transcribe") to setOf("transcribe_audio"),
        setOf("录屏", "screen record", "录制屏幕") to setOf("screen_record"),
        setOf("位置", "在哪", "附近", "地图", "location", "map") to
            setOf("get_location", "open_map"),
        setOf("前台", "当前应用", "foreground app") to setOf("get_foreground_app"),
        setOf("屏幕状态", "亮屏", "锁屏", "screen state") to setOf("get_screen_state"),
    )

    private const val MAX_CHAT_PLUS_TOOLS = 20

    fun route(
        mode: AppMode,
        specs: List<ToolSpec>,
        query: String?,
    ): List<ToolSpec> {
        if (mode != AppMode.CHAT_PLUS) return specs
        val readable = specs.filter { it.readOnly || it.alwaysAvailable }
        val byName = readable.associateBy { it.name }
        val selected = LinkedHashSet<String>()

        // Internal memory tools are always part of the agent's mind.
        readable.filter { it.alwaysAvailable }.forEach { selected += it.name }
        CHAT_PLUS_CORE.forEach { name -> if (name in byName) selected += name }

        val text = query.orEmpty().lowercase()
        for ((keywords, tools) in CHAT_PLUS_OPTIONAL) {
            if (selected.size >= MAX_CHAT_PLUS_TOOLS) break
            if (keywords.any { text.contains(it) }) {
                tools.forEach { name ->
                    if (selected.size < MAX_CHAT_PLUS_TOOLS && name in byName) {
                        selected += name
                    }
                }
            }
        }

        return selected.take(MAX_CHAT_PLUS_TOOLS)
            .mapNotNull { byName[it] }
            .sortedBy { it.name }
    }
}
