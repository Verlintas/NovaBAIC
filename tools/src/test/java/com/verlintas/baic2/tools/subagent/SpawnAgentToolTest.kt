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

package com.verlintas.baic2.tools.subagent

import com.verlintas.baic2.core.engine.ConfirmationGate
import com.verlintas.baic2.core.engine.TaintState
import com.verlintas.baic2.core.engine.ToolCatalog
import com.verlintas.baic2.core.engine.ToolRunContext
import com.verlintas.baic2.core.engine.ToolRunner
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ChatProvider
import com.verlintas.baic2.core.model.ChatRequest
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.core.model.ProviderId
import com.verlintas.baic2.core.model.StreamEvent
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.core.model.ToolTrust
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class SpawnAgentToolTest {

    private val config = ProviderConfig(
        provider = ProviderId.OPENAI_COMPATIBLE,
        baseUrl = "http://localhost/v1",
        apiKey = "test",
        model = "test-model",
    )

    private class ScriptedProvider(private val rounds: List<List<StreamEvent>>) : ChatProvider {
        private var cursor = 0

        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
            val script = rounds.getOrElse(cursor) { emptyList() }
            cursor++
            script.forEach { emit(it) }
        }
    }

    private fun toolRound(vararg calls: ToolCall): List<StreamEvent> =
        listOf(StreamEvent.ToolCallsDone(calls.toList()), StreamEvent.Done)

    private fun textRound(text: String): List<StreamEvent> =
        listOf(StreamEvent.TextDelta(text), StreamEvent.Done)

    private fun catalog(specs: List<ToolSpec>) = object : ToolCatalog {
        override fun specs(mode: AppMode): List<ToolSpec> = specs

        override fun find(name: String): ToolSpec? = specs.firstOrNull { it.name == name }
    }

    private fun <T> lazyOf(value: T): dagger.Lazy<T> = object : dagger.Lazy<T> {
        override fun get(): T = value
    }

    private val shellSpec = ToolSpec(
        name = "run_shell",
        description = "sh",
        parametersJson = """{"type":"object","properties":{"command":{"type":"string"}}}""",
        danger = DangerLevel.HIGH,
    )

    private val webSpec = ToolSpec(
        name = "web_read",
        description = "web",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string"}}}""",
        readOnly = true,
        untrustedOutput = true,
    )

    private fun spawn(
        provider: ChatProvider,
        specs: List<ToolSpec>,
        runner: ToolRunner,
    ) = SpawnAgentTool(
        providerFactory = { provider },
        toolCatalog = lazyOf(catalog(specs)),
        toolRunner = lazyOf(runner),
        confirmationGate = ConfirmationGate { false },
    )

    @Test
    fun cleanParentRunsChildTools() = runTest {
        val shell = ToolCall(id = "c1", name = "run_shell", argumentsJson = "{}")
        val provider = ScriptedProvider(
            listOf(
                toolRound(shell),
                textRound("done"),
            ),
        )
        var runnerCalls = 0
        val tool = spawn(provider, listOf(shellSpec)) { _, _ ->
            runnerCalls++
            ToolResult.Success("ran")
        }

        val result = tool.runChild("do the task", AppMode.MAX, ToolRunContext(config = config, mode = AppMode.MAX))

        assertTrue(result is ToolResult.Success)
        assertEquals(1, runnerCalls)
    }

    @Test
    fun taintedParentSpawnsATaintedChild() = runTest {
        val shell = ToolCall(id = "c1", name = "run_shell", argumentsJson = "{}")
        val provider = ScriptedProvider(
            listOf(
                toolRound(shell),
                textRound("stopped"),
            ),
        )
        var runnerCalls = 0
        val tool = spawn(provider, listOf(shellSpec)) { _, _ ->
            runnerCalls++
            ToolResult.Success("must not run")
        }

        val result = tool.runChild(
            task = "do the task",
            mode = AppMode.MAX,
            run = ToolRunContext(
                config = config,
                mode = AppMode.MAX,
                taint = TaintState().apply { tainted = true },
            ),
        )

        assertTrue(result is ToolResult.Success)
        assertEquals(0, runnerCalls, "the child must inherit the parent's taint")
        assertTrue((result as ToolResult.Success).output.contains("stopped"))
    }

    @Test
    fun childThatReadUntrustedContentReturnsAMarkedReport() = runTest {
        val web = ToolCall(id = "c1", name = "web_read", argumentsJson = "{}")
        val provider = ScriptedProvider(
            listOf(
                toolRound(web),
                textRound("summary of the page"),
            ),
        )
        val tool = spawn(provider, listOf(webSpec)) { _, _ -> ToolResult.Success("page text") }

        val result = tool.runChild("read the page", AppMode.MAX, ToolRunContext(config = config, mode = AppMode.MAX))

        assertTrue(result is ToolResult.Success)
        assertTrue(
            ToolTrust.isUntrusted((result as ToolResult.Success).output),
            "the parent must inherit the child's taint through the report",
        )
    }
}
