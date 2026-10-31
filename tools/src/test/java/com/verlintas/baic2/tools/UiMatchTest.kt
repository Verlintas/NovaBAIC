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

import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.device.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UiMatchTest {

    private fun node(text: String, x: Int = 0, y: Int = 0) = TextNode(text, x, y, x + 10, y + 10)

    @Test
    fun scoresExactPrefixAndSubstringInOrder() {
        assertEquals(0, uiMatchScore("Settings", "settings"))
        assertEquals(2, uiMatchScore("Settings", "sett"))
        assertEquals(3, uiMatchScore("Network settings", "settings"))
    }

    @Test
    fun scoresTypoWithinTwoEdits() {
        assertEquals(5, uiMatchScore("Settings", "setings"))
        assertNull(uiMatchScore("Settings", "xyz"))
    }

    @Test
    fun scoresQueryContainingShortLabel() {
        assertEquals(4, uiMatchScore("Wi-Fi", "wi-fi settings"))
    }

    @Test
    fun bestMatchesSortsByScoreAndDeduplicates() {
        val matches = uiBestMatches(
            listOf(
                node("Advanced settings", 1, 1),
                node("Settings", 2, 2),
                node("Settings", 2, 2),
                node("Wi-fi", 3, 3),
            ),
            "settings",
        )
        assertEquals(2, matches.size)
        assertEquals("Settings", matches.first().text)
        assertTrue(matches.last().text.contains("Advanced"))
    }

    @Test
    fun noMatchForEmptyInput() {
        assertNull(uiMatchScore("Settings", "  "))
        assertTrue(uiBestMatches(listOf(node("a")), "").isEmpty())
    }

    @Test
    fun uiControlAdvertisesUnboundedSequenceWithSafetyBudget() {
        val schema = UiControlTool().spec.parametersJson

        assertTrue(schema.contains("\"sequence\""))
        assertTrue(schema.contains("\"steps\""))
        assertTrue(!schema.contains("\"maxItems\""))
        assertTrue(schema.contains("\"snapshot\""))
        assertTrue(schema.contains("\"assert_text\""))
        assertTrue(schema.contains("\"read_text\""))
        assertTrue(schema.contains("\"ref\""))
    }

    @Test
    fun sequenceAcceptsMoreThanEightSteps() = runBlocking {
        val steps = (1..12).map {
            buildJsonObject { put("action", "tap"); put("text", "step$it") }
        }
        var calls = 0
        val result = UiControlTool().runSequence(steps) { _, _ ->
            calls++
            ToolResult.Success("ok")
        }

        assertTrue(result is ToolResult.Success)
        assertEquals(12, calls)
    }

    @Test
    fun sequenceKeepsReadTextBeyondPerStepSummary() = runBlocking {
        val body = "x".repeat(1_500)
        val steps = listOf(
            buildJsonObject { put("action", "tap"); put("ref", "e1") },
            buildJsonObject { put("action", "read_text") },
        )
        val result = UiControlTool().runSequence(steps) { action, _ ->
            if (action == "read_text") ToolResult.Success(body) else ToolResult.Success("ok")
        }

        assertTrue(result is ToolResult.Success)
        assertTrue(result.output.contains(body))
    }

    @Test
    fun sequenceFailureOutputIsBounded() = runBlocking {
        val steps = listOf(
            buildJsonObject { put("action", "read_text") },
            buildJsonObject { put("action", "tap") },
        )
        val result = UiControlTool().runSequence(steps) { action, _ ->
            if (action == "read_text") {
                ToolResult.Success("x".repeat(20_000))
            } else {
                ToolResult.Failure("boom")
            }
        }

        assertTrue(result is ToolResult.Failure)
        assertTrue(result.reason.length <= 12_000)
    }

    @Test
    fun sequenceClampsWaitToRemainingBudget() = runBlocking {
        val steps = listOf(
            buildJsonObject { put("action", "wait_for"); put("timeout_ms", 999_999) },
            buildJsonObject { put("action", "tap") },
        )
        var observed = -1
        val result = UiControlTool().runSequence(steps) { _, step ->
            if (step["action"]?.toString()?.contains("wait_for") == true) {
                observed = (step["timeout_ms"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toInt() ?: -1
            }
            ToolResult.Success("ok")
        }

        assertTrue(result is ToolResult.Success)
        assertTrue(observed in 1..300_000)
    }

    @Test
    fun sequenceStopsAtFirstFailedStep() = runBlocking {
        val steps = listOf(
            buildJsonObject { put("action", "tap") },
            buildJsonObject { put("action", "type") },
            buildJsonObject { put("action", "press_key") },
        )
        var calls = 0
        val result = UiControlTool().runSequence(steps) { action, _ ->
            calls++
            if (action == "type") {
                ToolResult.Failure("field refused input")
            } else {
                ToolResult.Success("ok")
            }
        }

        assertTrue(result is ToolResult.Failure)
        assertTrue(result.reason.contains("step 2"))
        assertEquals(2, calls)
    }
}
