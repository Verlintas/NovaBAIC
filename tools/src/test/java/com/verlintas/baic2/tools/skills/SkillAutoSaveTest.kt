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

package com.verlintas.baic2.tools.skills

import com.verlintas.baic2.core.model.SkillStep
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolCallStatus
import com.verlintas.baic2.tools.UiRefRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillAutoSaveTest {

    private var nextId = 0

    private fun call(
        name: String = "ui_control",
        args: String,
        status: ToolCallStatus = ToolCallStatus.DONE,
    ) = ToolCall(
        id = "c${nextId++}",
        name = name,
        argumentsJson = args,
        status = status,
    )

    private fun sequence(steps: String) =
        """{"action":"sequence","steps":[$steps]}"""

    @Test
    fun requiresAtLeastTwoSequenceSteps() {
        val two = call(args = sequence("""{"action":"tap","text":"Search"},""" +
            """{"action":"assert_text","text":"Done"}"""))
        val one = call(args = sequence("""{"action":"tap","text":"Search"}"""))
        val tap = call(args = """{"action":"tap","text":"Search"}""")

        assertTrue(isReplayableSequence(two))
        assertFalse(isReplayableSequence(one))
        assertFalse(isReplayableSequence(tap))
    }

    @Test
    fun autoSaveNeedsASuccessfulSequenceButToleratesEarlierFailures() {
        val sequence = call(
            args = sequence("""{"action":"tap","text":"Search"},""" +
                """{"action":"type","value":"battery","submit":true}"""),
        )
        val failed = call(
            name = "ui_control",
            args = """{"action":"tap","text":"Old target"}""",
            status = ToolCallStatus.FAILED,
        )
        val read = call(name = "web_read", args = """{"url":"https://example.com"}""")

        assertTrue(shouldAutoSaveSkill(listOf(sequence, read)))
        assertTrue(shouldAutoSaveSkill(listOf(failed, sequence)))
        assertTrue(shouldAutoSaveSkill(listOf(sequence, read.copy(status = ToolCallStatus.FAILED))))
        assertFalse(shouldAutoSaveSkill(emptyList()))
        assertFalse(shouldAutoSaveSkill(listOf(sequence.copy(status = ToolCallStatus.FAILED))))
        assertFalse(shouldAutoSaveSkill(listOf(failed)))
        // Failed steps are never part of the recorded recipe.
        assertEquals(listOf("ui_control"), filterAutoSaveCalls(listOf(failed, sequence)).map { it.name })
    }

    @Test
    fun autoSaveDropsMemoryAndObservations() {
        val open = call(name = "open_settings", args = """{"page":"settings"}""")
        val snapshot = call(args = """{"action":"snapshot"}""")
        val sequence = call(
            args = sequence("""{"action":"tap","text":"Search"},""" +
                """{"action":"type","value":"display","submit":true}"""),
        )
        val memory = call(
            name = "core_memory_update",
            args = """{"slot":"context","set":"x"}""",
        )
        val plan = call(name = "plan_update", args = """{"steps":[]}""")
        val read = call(args = """{"action":"read_text"}""")

        val filtered = filterAutoSaveCalls(
            listOf(open, snapshot, sequence, memory, plan, read),
        )

        assertEquals(listOf("open_settings", "ui_control"), filtered.map { it.name })
        assertEquals(sequence, filtered[1])
    }

    @Test
    fun autoStepsResolveRefsFromAFilteredSnapshot() {
        UiRefRegistry.resetForTest()
        val snapshot = ToolCall(
            id = "snap",
            name = "ui_control",
            argumentsJson = """{"action":"snapshot"}""",
            result = """
                Snapshot of com.android.settings (1 elements):
                [e1] Search settings — EditText (input)
            """.trimIndent(),
            status = ToolCallStatus.DONE,
        )
        val tap = call(
            args = sequence("""{"action":"tap","ref":"e1"},""" +
                """{"action":"type","value":"display","submit":true}"""),
        )

        val steps = buildAutoSteps(listOf(snapshot, tap))

        assertEquals(1, steps.size)
        assertFalse(steps.first().argsJson.contains("\"ref\""))
        assertTrue(steps.first().argsJson.contains("\"text\":\"Search settings\""))
        UiRefRegistry.resetForTest()
    }

    @Test
    fun autoStepsParameterizeTypedValues() {
        val steps = listOf(
            SkillStep(
                tool = "ui_control",
                argsJson = sequence(
                    """{"action":"tap","text":"Search settings"},""" +
                        """{"action":"type","value":"display","submit":true},""" +
                        """{"action":"tap","text":"Search settings"},""" +
                        """{"action":"type","value":"battery","submit":true}""",
                ),
            ),
        )

        val parameterized = parameterizeAutoSteps(steps)
        val json = parameterized.steps.single().argsJson

        assertFalse(json.contains("display"))
        assertFalse(json.contains("battery"))
        assertTrue(json.contains("{{input1}}"))
        assertTrue(json.contains("{{input2}}"))
        assertTrue(parameterized.parametersJson.contains("\"input1\""))
        assertTrue(parameterized.parametersJson.contains("\"required\""))
    }

    @Test
    fun credentialFlowsAreNotAutoSaved() {
        val password = call(
            args = sequence("""{"action":"tap","text":"Password"},""" +
                """{"action":"type","value":"hunter2","submit":true}"""),
        )
        val token = call(
            args = sequence("""{"action":"tap","text":"API key"},""" +
                """{"action":"type","value":"sk-test-token"}"""),
        )
        val search = call(
            args = sequence("""{"action":"tap","text":"Search settings"},""" +
                """{"action":"type","value":"display","submit":true}"""),
        )

        assertTrue(containsSensitiveInput(listOf(password)))
        assertTrue(containsSensitiveInput(listOf(token)))
        assertFalse(containsSensitiveInput(listOf(search)))
    }

    @Test
    fun autoNameUsesFirstTapLabel() {
        val steps = listOf(
            SkillStep(
                tool = "ui_control",
                argsJson = sequence("""{"action":"tap","text":"Search settings"},""" +
                    """{"action":"type","value":"battery"}"""),
            ),
        )
        assertEquals("Auto · Search settings", autoSkillName(steps))

        val noTap = listOf(
            SkillStep(
                tool = "ui_control",
                argsJson = sequence("""{"action":"type","value":"battery"},""" +
                    """{"action":"press_key","value":"enter"}"""),
            ),
        )
        assertNull(autoSkillName(noTap))
    }

    @Test
    fun signatureIsStableAndSensitiveToSteps() {
        val steps = listOf(
            SkillStep("ui_control", sequence("""{"action":"tap","text":"A"},""" +
                """{"action":"tap","text":"B"}""")),
        )
        val same = listOf(
            SkillStep("ui_control", sequence("""{"action":"tap","text":"A"},""" +
                """{"action":"tap","text":"B"}""")),
        )
        val changed = listOf(
            SkillStep("ui_control", sequence("""{"action":"tap","text":"A"},""" +
                """{"action":"tap","text":"C"}""")),
        )

        assertEquals(16, skillSignature(steps).length)
        assertEquals(skillSignature(steps), skillSignature(same))
        assertNotEquals(skillSignature(steps), skillSignature(changed))
    }
}
