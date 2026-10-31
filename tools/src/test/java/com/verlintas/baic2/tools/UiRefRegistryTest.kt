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

import com.verlintas.baic2.device.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiRefRegistryTest {

    private fun node(text: String, x: Int = 10, y: Int = 20) =
        TextNode(text, x, y, x + 100, y + 40, editable = false)

    @Test
    fun replaceAssignsStableIdsAndExpires() {
        UiRefRegistry.resetForTest()
        val entries = UiRefRegistry.replace(
            listOf(node("Battery Saver"), node("Battery usage", y = 200)),
            now = 1_000L,
        )

        assertEquals(listOf("e1", "e2"), entries.map { it.id })
        assertEquals("Battery Saver", UiRefRegistry.find("E1", now = 2_000L)?.node?.text)
        assertNull(UiRefRegistry.find("e1", now = 1_000L + UiRefRegistry.TTL_MS + 1))
        UiRefRegistry.resetForTest()
    }

    @Test
    fun newSnapshotReassignsIdsToCurrentScreen() {
        UiRefRegistry.resetForTest()
        UiRefRegistry.replace(listOf(node("Search")))
        assertEquals("Search", UiRefRegistry.label("e1"))

        UiRefRegistry.replace(listOf(node("Battery")))
        assertEquals("Battery", UiRefRegistry.find("e1")?.node?.text)
        assertEquals("Battery", UiRefRegistry.label("e1"))
        UiRefRegistry.resetForTest()
    }

    @Test
    fun materializeRewritesTopLevelAndNestedRefs() {
        val top = materializeUiRefs("""{"action":"tap","ref":"e2"}""") { id ->
            if (id == "e2") "Battery Saver" else null
        }
        assertTrue(top.contains("\"text\":\"Battery Saver\""))
        assertFalse(top.contains("\"ref\""))

        val nested = materializeUiRefs(
            """{"action":"sequence","steps":[{"action":"tap","ref":"e1"},""" +
                """{"action":"assert_text","text":"Battery Saver"}]}""",
        ) { id -> if (id == "e1") "Battery" else null }
        assertTrue(nested.contains("\"text\":\"Battery\""))
        assertFalse(nested.contains("\"ref\""))
        assertTrue(nested.contains("\"assert_text\""))
    }

    @Test
    fun snapshotLabelsParseDirectAndSequencedSnapshots() {
        val direct = """
            Snapshot of com.android.settings (2 elements; refs expire in 120s):
            [e1] Back — ImageButton (tap)
            [e2] Battery Saver — LinearLayout (tap)
            Use tap/long_press with ref=<id>.
        """.trimIndent()
        assertEquals(
            mapOf("e1" to "Back", "e2" to "Battery Saver"),
            snapshotRefLabels(direct),
        )

        val sequenced = """
            Sequence completed (2 steps):
            1. snapshot: Snapshot of com.android.settings (1 elements):
            [e1] Search settings — ViewGroup (tap)
            2. tap: Tapped (10, 20)
        """.trimIndent()
        assertEquals(mapOf("e1" to "Search settings"), snapshotRefLabels(sequenced))
        assertTrue(snapshotRefLabels(null).isEmpty())
    }

    @Test
    fun materializeLeavesUnknownRefsAndBadJsonAlone() {
        val unknown = """{"action":"tap","ref":"e9"}"""
        assertEquals(unknown, materializeUiRefs(unknown) { null })
        assertEquals("not-json", materializeUiRefs("not-json") { "x" })
    }
}
