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

import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatProvider
import com.verlintas.baic2.core.model.ChatRequest
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.core.model.ProviderError
import com.verlintas.baic2.core.model.ProviderId
import com.verlintas.baic2.core.model.StreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class AuxiliaryTasksTest {

    private val config = ProviderConfig(
        provider = ProviderId.OPENAI_COMPATIBLE,
        baseUrl = "http://localhost/v1",
        apiKey = "test",
        model = "test-model",
    )

    private class ScriptedProvider(private val events: List<StreamEvent>) : ChatProvider {
        var lastRequest: ChatRequest? = null

        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
            lastRequest = request
            events.forEach { emit(it) }
        }
    }

    @Test
    fun collectsTextAndForcesNoTools() = runTest {
        val provider = ScriptedProvider(
            listOf(
                StreamEvent.ThinkingDelta("ignored"),
                StreamEvent.TextDelta("Hello "),
                StreamEvent.TextDelta("title"),
                StreamEvent.Done,
            ),
        )
        val tasks = AuxiliaryTasks({ provider })

        val result = tasks.complete(config, "system", "user", maxTokens = 64, temperature = 0.1)

        assertEquals("Hello title", result)
        val request = requireNotNull(provider.lastRequest)
        assertTrue(request.tools.isEmpty())
        assertEquals(64, request.config.maxTokens)
        assertEquals(0.1, request.config.temperature)
        assertTrue(!request.config.reasoning)
    }

    @Test
    fun providerFailureBecomesAuxiliaryFailure() = runTest {
        val provider = ScriptedProvider(
            listOf(StreamEvent.Failed(ProviderError(ProviderError.Kind.AUTH, "bad key"))),
        )
        val tasks = AuxiliaryTasks({ provider })

        val failure = assertFailsWith<AuxiliaryFailure> {
            tasks.complete(config, "system", "user")
        }

        assertEquals("bad key", failure.error.message)
    }

    @Test
    fun transcriptRenderingIsCompact() {
        val messages = listOf(
            ChatMessage(role = ChatRole.USER, content = "hi"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "a".repeat(600)),
            ChatMessage(role = ChatRole.TOOL, content = "result", toolName = "get_weather"),
        )

        val rendered = AuxiliaryTasks.renderTranscript(messages, perMessageLimit = 10)

        assertEquals(3, rendered.lines().size)
        assertTrue(rendered.startsWith("USER: hi"))
        assertTrue(rendered.contains("ASSISTANT: aaaaaaaaaa"))
        assertTrue(rendered.contains("TOOL:get_weather:"))
    }

    @Test
    fun parseCuratorPlanReadsAllSections() {
        val raw = """
            {"remember":[{"kind":"preference","content":"likes dark themes","importance":4,"when":"2026-09-12"}],
             "revise":[{"id":12,"content":"moved to Shanghai","importance":5}],
             "forget":[9],
             "core_user":"Alex, Chinese, prefers concise replies",
             "core_context":"preparing for an interview"}
        """.trimIndent()

        val plan = AuxiliaryTasks.parseCuratorPlan(raw)

        assertEquals(1, plan.remember.size)
        assertEquals("preference", plan.remember[0].kind)
        assertEquals("likes dark themes", plan.remember[0].content)
        assertEquals(4, plan.remember[0].importance)
        assertEquals("2026-09-12", plan.remember[0].whenRaw)
        assertEquals(12L, plan.revise[0].id)
        assertEquals("moved to Shanghai", plan.revise[0].content)
        assertEquals(5, plan.revise[0].importance)
        assertEquals(listOf(9L), plan.forget)
        assertEquals("Alex, Chinese, prefers concise replies", plan.coreUser)
        assertEquals("preparing for an interview", plan.coreContext)
    }

    @Test
    fun parseCuratorPlanToleratesProseAndFences() {
        val raw = "Sure, here it is:\n```json\n{\"remember\":[{\"content\":\"uses a Pixel\"}]}\n```\nDone."

        val plan = AuxiliaryTasks.parseCuratorPlan(raw)

        assertEquals(1, plan.remember.size)
        assertEquals("uses a Pixel", plan.remember[0].content)
        assertEquals(3, plan.remember[0].importance)
        assertEquals("", plan.remember[0].kind)
    }

    @Test
    fun parseCuratorPlanRejectsGarbage() {
        assertTrue(AuxiliaryTasks.parseCuratorPlan("[broken").isEmpty)
        assertTrue(AuxiliaryTasks.parseCuratorPlan("no json here").isEmpty)
        assertTrue(AuxiliaryTasks.parseCuratorPlan("{\"remember\":[{\"nope\":1}]}").isEmpty)
        assertTrue(AuxiliaryTasks.parseCuratorPlan("").isEmpty)
    }

    @Test
    fun parseCuratorPlanClampsAndFilters() {
        val many = (1..9).joinToString(",", prefix = "{\"remember\":[", postfix = "]}") {
            "{\"content\":\"fact $it\"}"
        }
        assertEquals(5, AuxiliaryTasks.parseCuratorPlan(many).remember.size)

        val plan = AuxiliaryTasks.parseCuratorPlan(
            """{"remember":[{"content":"x","importance":99}],"forget":[-1,0,3]}""",
        )
        assertEquals(5, plan.remember[0].importance)
        assertEquals(listOf(3L), plan.forget)

        val withoutId = AuxiliaryTasks.parseCuratorPlan("""{"revise":[{"content":"no id"}]}""")
        assertTrue(withoutId.revise.isEmpty())
    }

    @Test
    fun parseCuratorPlanReadsSourceEntitiesAndRehearsal() {
        val raw = """
            {"remember":[{"content":"张伟喜欢咖啡","source":"assistant","entities":["张伟","咖啡","","x","y","z","w"]}],
             "rehearse_keep":[3,4]}
        """.trimIndent()

        val plan = AuxiliaryTasks.parseCuratorPlan(raw)

        assertEquals("assistant", plan.remember[0].source)
        assertEquals(listOf("张伟", "咖啡", "x", "y", "z", "w"), plan.remember[0].entities)
        assertEquals(listOf(3L, 4L), plan.rehearseKeep)
    }

    @Test
    fun parseCuratorPlanReadsAliases() {
        val raw = """
            {"aliases":[{"alias":"妈妈","entity":"张兰"},{"alias":"zhanglan","entity":"张兰"},
             {"alias":"","entity":"x"},{"alias":"noEntity","entity":""}],
             "remember":[]}
        """.trimIndent()

        val plan = AuxiliaryTasks.parseCuratorPlan(raw)

        assertEquals(
            listOf(CuratorAlias("妈妈", "张兰"), CuratorAlias("zhanglan", "张兰")),
            plan.aliases,
        )
        assertTrue(!plan.isEmpty)
    }
}
