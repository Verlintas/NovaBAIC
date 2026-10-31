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

import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class ToolRegistryTest {

    private fun fakeTool(name: String, readOnly: Boolean) = object : DeviceTool {
        override val spec = ToolSpec(
            name = name,
            description = name,
            readOnly = readOnly,
            danger = DangerLevel.LOW,
        )

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
            ToolResult.Success("ok")
    }

    private fun memoryTool(name: String) = object : DeviceTool {
        override val spec = ToolSpec(
            name = name,
            description = name,
            readOnly = true,
            danger = DangerLevel.LOW,
            alwaysAvailable = true,
        )

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
            ToolResult.Success("ok")
    }

    private val registry = ToolRegistry(
        setOf(
            fakeTool("get_weather", readOnly = true),
            fakeTool("open_app", readOnly = false),
            fakeTool("compute", readOnly = true),
        ),
    )

    @Test
    fun chatModeExposesNoTools() {
        assertTrue(registry.specs(AppMode.CHAT).isEmpty())
    }

    @Test
    fun chatPlusExposesOnlyReadOnlyTools() {
        val names = registry.specs(AppMode.CHAT_PLUS).map { it.name }.sorted()
        assertEquals(listOf("compute", "get_weather"), names)
    }

    @Test
    fun actAndMaxExposeEverything() {
        assertEquals(3, registry.specs(AppMode.ACT).size)
        assertEquals(3, registry.specs(AppMode.MAX).size)
    }

    @Test
    fun findResolvesByName() {
        assertEquals("open_app", registry.find("open_app")?.name)
        assertEquals(null, registry.find("nope"))
    }

    @Test
    fun internalMemoryToolsAreAvailableInEveryMode() {
        val withMemory = ToolRegistry(
            setOf(
                fakeTool("open_app", readOnly = false),
                memoryTool("memory_search"),
                memoryTool("memory_read"),
            ),
        )
        assertEquals(
            listOf("memory_read", "memory_search"),
            withMemory.specs(AppMode.CHAT).map { it.name }.sorted(),
        )
        assertEquals(
            listOf("memory_read", "memory_search"),
            withMemory.specs(AppMode.CHAT_PLUS).map { it.name }.sorted(),
        )
        assertEquals(3, withMemory.specs(AppMode.ACT).size)
        assertEquals(3, withMemory.specs(AppMode.MAX).size)
    }
}
