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
import kotlin.test.Test
import kotlin.test.assertTrue

class PromptCompilerTest {

    @Test
    fun stablePrefixDoesNotCarryPerTurnState() {
        val plan = PromptCompiler.compile(
            mode = AppMode.CHAT_PLUS,
            custom = "",
            planContext = "1. search\n2. answer",
            memoryContext = "<memory>likes tea</memory>",
            now = 1_000_000L,
        )

        assertTrue(plan.stablePrefix.contains("Aviiya"))
        assertTrue(plan.stablePrefix.contains("research mode"))
        assertTrue("Current date and time" !in plan.stablePrefix)
        assertTrue("1. search" !in plan.stablePrefix)
        assertTrue("likes tea" !in plan.stablePrefix)
    }

    @Test
    fun runtimeSuffixCarriesTimePlanAndMemory() {
        val plan = PromptCompiler.compile(
            mode = AppMode.CHAT_PLUS,
            custom = "",
            planContext = "1. search\n2. answer",
            memoryContext = "<memory>likes tea</memory>",
            now = 1_000_000L,
        )

        assertTrue(plan.runtimeSuffix.contains("Current date and time"))
        assertTrue(plan.runtimeSuffix.contains("1. search"))
        assertTrue(plan.runtimeSuffix.contains("likes tea"))
        assertTrue(plan.full.contains(plan.stablePrefix))
        assertTrue(plan.full.contains(plan.runtimeSuffix))
    }

    @Test
    fun runtimeCarriesMessageTimeAndCachedFacts() {
        val runtime = PromptCompiler.runtime(
            mode = AppMode.CHAT,
            planContext = null,
            memoryContext = null,
            now = 10_000_000L,
            messageAt = 1_000_000L,
            location = "杭州",
            weather = "小雨 18°C",
        )

        assertTrue(runtime.contains("User message sent"))
        assertTrue(runtime.contains("杭州"))
        assertTrue(runtime.contains("小雨"))
    }

    @Test
    fun customPromptStaysFirst() {
        val prompt = renderSystemPrompt(AppMode.CHAT, "You are a pirate.", null)

        assertTrue(prompt.startsWith("You are a pirate."))
        assertTrue(prompt.contains("Aviiya"))
    }

    @Test
    fun chatModeDoesNotGetAResearchPlan() {
        val plan = PromptCompiler.compile(
            mode = AppMode.CHAT,
            custom = "",
            planContext = "should not appear",
        )

        assertTrue("should not appear" !in plan.full)
    }
}
