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

package com.verlintas.baic2.tools.apps

import com.verlintas.baic2.device.api.VirtualDisplayBridge
import com.verlintas.baic2.device.api.VirtualDisplaySession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppResearchToolTest {

    private val displays = object : VirtualDisplayBridge {
        override fun available(): Boolean = false

        override suspend fun ensure(): Result<VirtualDisplaySession> =
            Result.failure(IllegalStateException("not available"))

        override suspend fun capture(): Result<ByteArray> =
            Result.failure(IllegalStateException("not available"))

        override suspend fun launch(component: String): Result<Unit> =
            Result.failure(IllegalStateException("not available"))

        override suspend fun tap(x: Int, y: Int): Result<Unit> =
            Result.failure(IllegalStateException("not available"))

        override suspend fun swipe(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            durationMs: Int,
        ): Result<Unit> = Result.failure(IllegalStateException("not available"))

        override suspend fun keyEvent(keyCode: Int): Result<Unit> =
            Result.failure(IllegalStateException("not available"))

        override suspend fun typeText(text: String): Result<Unit> =
            Result.failure(IllegalStateException("not available"))

        override fun release() = Unit
    }

    private val lexicon = ResearchLexicon(
        searchTerms = listOf("search", "搜索", "搜一搜"),
        chrome = setOf("back", "apps", "search", "返回", "取消", "搜索"),
        loginTerms = listOf("sign in", "log in", "登录后", "请登录"),
        captchaTerms = listOf("captcha", "验证码"),
        riskTerms = listOf("unusual traffic", "操作频繁"),
    )

    private val knowledge = object : AppKnowledgeRepository {
        override suspend fun get(packageName: String): AppKnowledgeCard? = null
        override suspend fun recordSearch(packageName: String, searchViewId: String?) = Unit
    }

    private fun newTool() = AppResearchTool(displays, AppResearchSessions(), knowledge)

    @Test
    fun prioritizeDropsChromeAndPutsQueryMatchesFirst() {
        val lines = listOf("Back", "Battery", "Use Battery Saver", "Settings", "Apps")
        val prioritized = newTool().prioritize(lines, "battery", lexicon.chrome)

        assertTrue(prioritized.first().contains("Battery"))
        assertTrue(prioritized.none { it == "Back" })
        assertTrue(prioritized.none { it == "Apps" })
        assertTrue(prioritized.contains("Use Battery Saver"))
    }

    @Test
    fun appResearchIsAReadOnlyUntrustedResearchVerb() {
        val spec = newTool().spec

        assertEquals("app_research", spec.name)
        assertTrue(spec.readOnly)
        assertTrue(spec.untrustedOutput)
        assertTrue(spec.parametersJson.contains("\"max_screens\""))
        assertTrue(spec.parametersJson.contains("\"query\""))
        assertTrue(spec.parametersJson.contains("\"open\""))
        assertTrue(spec.parametersJson.contains("\"open_top\""))
        assertTrue(spec.parametersJson.contains("\"session\""))
    }

    @Test
    fun textDeltaKeepsOnlyMovedLines() {
        val delta = newTool().textDelta(
            previous = "Battery\nBattery Saver\nOld result",
            current = listOf("Battery", "Battery Saver", "New result"),
        )

        assertEquals(listOf("New result"), delta.added)
        assertEquals(listOf("Old result"), delta.removed)
    }

    @Test
    fun buildOutputSwitchesToDeltaForSessionFollowUps() {
        val output = newTool().buildOutput(
            label = "Settings",
            packageName = "com.android.settings",
            query = "battery",
            screens = 1,
            lines = listOf("Battery", "New result"),
            session = "abc123",
            previousText = "Battery\nOld result",
        )

        assertTrue(output.contains("Delta from previous observation:"))
        assertTrue(output.contains("+ New result"))
        assertTrue(output.contains("- Old result"))
        assertTrue(!output.contains("Visible text:"))
    }

    @Test
    fun sessionsExpireAndCanBeLookedUp() {
        val sessions = AppResearchSessions()
        val created = sessions.put("Settings", "com.android.settings", "battery", displayId = 31, now = 1_000L)

        assertEquals(created, sessions.get(created.id, now = 2_000L))
        assertEquals(null, sessions.get("", now = 2_000L))
        assertEquals(null, sessions.get("missing", now = 2_000L))

        sessions.touch(created.id, now = 20_000L)
        assertEquals(20_000L, sessions.get(created.id, now = 21_000L)?.updatedAt)
        assertEquals(null, sessions.get(created.id, now = 700_001L))
    }

    @Test
    fun sessionCanBeRetargetedToANewQuery() {
        val sessions = AppResearchSessions()
        val created = sessions.put(
            "Settings",
            "com.android.settings",
            "battery",
            displayId = 31,
            opened = true,
        )

        sessions.updateQueryAndText(
            id = created.id,
            query = "display",
            text = "Display settings",
            opened = false,
            now = 5_000L,
        )

        val updated = sessions.get(created.id, now = 6_000L)
        assertEquals("display", updated?.query)
        assertEquals("Display settings", updated?.lastText)
        assertEquals(false, updated?.opened)
        assertEquals(5_000L, updated?.updatedAt)
    }

    @Test
    fun outputSeparatesSearchResultsFromOpenedDetail() {
        val output = newTool().buildOutput(
            label = "Settings",
            packageName = "com.android.settings",
            query = "battery",
            screens = 1,
            lines = listOf("Battery Saver", "Battery usage"),
            opened = "Battery Saver",
            detail = "Battery Saver\nUse adaptive battery",
            session = "abc123",
        )

        assertTrue(output.contains("Session: abc123"))
        assertTrue(output.contains("Opened: Battery Saver"))
        assertTrue(output.contains("Detail:"))
        assertTrue(output.contains("Use adaptive battery"))
    }

    @Test
    fun lexiconOnlyClassifiesChromeAndHandoffPages() {
        assertTrue(lexicon.isChrome("Back"))
        assertTrue(lexicon.isChrome("返回"))
        assertEquals("login", lexicon.blockedReason("Please log in"))
        assertEquals("captcha", lexicon.blockedReason("验证码"))
        assertEquals(null, lexicon.blockedReason("Battery Saver"))
    }
}
