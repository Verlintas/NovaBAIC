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

package com.verlintas.baic2.mcp

import com.verlintas.baic2.core.data.repository.McpServerRepository
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.McpServer
import com.verlintas.baic2.core.model.McpToolInfo
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import com.verlintas.baic2.tools.ToolRegistry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.sync.withLock

sealed interface McpServerStatus {
    data class Connected(val toolCount: Int) : McpServerStatus

    data class Failed(val reason: String) : McpServerStatus

    data object Disabled : McpServerStatus
}

/** Bridges MCP servers into the tool registry. */
@Singleton
class McpManager @Inject constructor(
    private val repository: McpServerRepository,
    private val client: McpClient,
    private val registry: dagger.Lazy<ToolRegistry>,
) {

    private val registeredByServer = ConcurrentHashMap<Long, List<String>>()
    private val sessions = ConcurrentHashMap<Long, McpSession>()

    private val _status = MutableStateFlow<Map<Long, McpServerStatus>>(emptyMap())
    val status: StateFlow<Map<Long, McpServerStatus>> = _status.asStateFlow()

    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    /** Reconnects every enabled server; safe to call repeatedly. */
    suspend fun refresh() = refreshMutex.withLock {
        disconnectAll()
        repository.getAll().forEach { server ->
            if (!server.enabled) {
                _status.update { it + (server.id to McpServerStatus.Disabled) }
                return@forEach
            }
            connect(server)
        }
    }

    suspend fun test(server: McpServer): Result<Int> = client.initialize(server).mapCatching { session ->
        client.listTools(session).getOrThrow().size
    }

    private suspend fun connect(server: McpServer) {
        client.initialize(server)
            .onSuccess { session -> sessions[server.id] = session }
            .onFailure { failure ->
                _status.update {
                    it + (server.id to McpServerStatus.Failed(failure.message ?: "connect failed"))
                }
            }
        val session = sessions[server.id] ?: return
        client.listTools(session)
            .onSuccess { tools ->
                val adapters = tools.mapNotNull { info ->
                    if (registry.get().find(info.name) != null) {
                        null // never shadow an existing tool (builtin or another server)
                    } else {
                        McpToolAdapter(info = info, session = session, client = client)
                    }
                }
                registry.get().registerDynamic(adapters)
                registeredByServer[server.id] = adapters.map { it.spec.name }
                _status.update { it + (server.id to McpServerStatus.Connected(tools.size)) }
            }
            .onFailure { failure ->
                _status.update {
                    it + (server.id to McpServerStatus.Failed(failure.message ?: "tools/list failed"))
                }
            }
    }

    fun disconnectAll() {
        registeredByServer.forEach { (_, names) -> registry.get().unregisterDynamic(names) }
        registeredByServer.clear()
        sessions.clear()
        _status.value = emptyMap()
    }
}

/** Exposes one MCP tool through the DeviceTool contract. */
class McpToolAdapter(
    private val info: McpToolInfo,
    private val session: McpSession,
    private val client: McpClient,
) : DeviceTool {

    override val spec = ToolSpec(
        name = info.name,
        description = info.description.ifBlank { "MCP tool from ${session.server.name}" },
        parametersJson = info.inputSchemaJson,
        readOnly = info.readOnly,
        danger = DangerLevel.MEDIUM,
        parallelSafe = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        client.callTool(session, info.name, arguments.toString()).fold(
            onSuccess = { ToolResult.Success(it.take(6_000)) },
            onFailure = { failure -> ToolResult.Failure(failure.message ?: "MCP call failed") },
        )
}
