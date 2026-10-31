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
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRouterTest {

    private fun read(name: String) = ToolSpec(name = name, description = name, readOnly = true)

    private fun memory(name: String) =
        ToolSpec(name = name, description = name, alwaysAvailable = true)

    private val specs = listOf(
        read("web_search"),
        read("web_read"),
        read("app_research"),
        read("spawn_agent"),
        read("screen_ocr"),
        read("plan_update"),
        read("ask_user"),
        read("compute"),
        read("files"),
        read("device_info"),
        read("get_app_usage"),
        memory("memory_search"),
        memory("memory_read"),
        memory("memory_write"),
        ToolSpec(name = "ui_control", description = "write"),
        ToolSpec(name = "run_shell", description = "shell", danger = DangerLevel.HIGH),
    )

    @Test
    fun chatPlusKeepsTheResearchCoreAndInternalMemory() {
        val names = ToolRouter.route(AppMode.CHAT_PLUS, specs, "帮我研究一下这个问题")
            .map { it.name }

        assertTrue("web_search" in names)
        assertTrue("web_read" in names)
        assertTrue("app_research" in names)
        assertTrue("spawn_agent" in names)
        assertTrue("screen_ocr" in names)
        assertTrue("plan_update" in names)
        assertTrue("memory_search" in names)
        assertTrue("memory_write" in names)
        assertTrue("ui_control" !in names)
        assertTrue("run_shell" !in names)
        assertTrue("files" !in names, "unmatched optional tools stay out")
    }

    @Test
    fun chatPlusLoadsAnOptionalToolOnlyWhenTheRequestMentionsIt() {
        val fileNames = ToolRouter.route(AppMode.CHAT_PLUS, specs, "帮我看看这个 PDF 文件")
            .map { it.name }
        val usageNames = ToolRouter.route(AppMode.CHAT_PLUS, specs, "我这周的应用使用时间是多少")
            .map { it.name }

        assertTrue("files" in fileNames)
        assertTrue("get_app_usage" in usageNames)
    }

    @Test
    fun executionModesKeepTheWholeCatalog() {
        assertEquals(specs.map { it.name }, ToolRouter.route(AppMode.ACT, specs, "x").map { it.name })
        assertEquals(specs.map { it.name }, ToolRouter.route(AppMode.MAX, specs, "x").map { it.name })
    }

    @Test
    fun chatPlusNeverExposesMoreThanTheBudget() {
        val many = (1..25).map { memory("memory_$it") } +
            listOf(read("web_search"), read("web_read"))
        val names = ToolRouter.route(AppMode.CHAT_PLUS, many, "file 文件 weather 天气").map { it.name }

        assertTrue(names.size <= 20)
    }
}
