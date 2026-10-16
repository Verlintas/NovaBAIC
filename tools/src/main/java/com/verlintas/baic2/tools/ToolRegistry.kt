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

import com.verlintas.baic2.core.engine.ToolCatalog
import com.verlintas.baic2.core.engine.ToolRunContext
import com.verlintas.baic2.core.engine.ToolRunner
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards DeviceTool>,
) : ToolCatalog {

    private val byName: Map<String, DeviceTool> = tools.associateBy { it.spec.name }
    private val dynamic = java.util.concurrent.ConcurrentHashMap<String, DeviceTool>()

    private fun allTools(): Map<String, DeviceTool> = byName + dynamic

    override fun specs(mode: AppMode): List<ToolSpec> {
        val tools = when (mode) {
            AppMode.CHAT -> allTools().values.filter { it.spec.alwaysAvailable }
            AppMode.CHAT_PLUS -> allTools().values
                .filter { it.spec.readOnly || it.spec.alwaysAvailable }

            AppMode.ACT, AppMode.MAX -> allTools().values
        }
        // Deterministic order keeps the serialized tool block stable across
        // turns, so provider prefix caching actually hits.
        return tools.map { it.spec }.sortedBy { it.name }
    }

    override fun find(name: String): ToolSpec? = allTools()[name]?.spec

    fun tool(name: String): DeviceTool? = allTools()[name]

    fun registerDynamic(tools: List<DeviceTool>) {
        tools.forEach { tool -> dynamic[tool.spec.name] = tool }
    }

    fun unregisterDynamic(names: Collection<String>) {
        names.forEach { dynamic.remove(it) }
    }

    val toolNames: List<String> get() = allTools().keys.sorted()
}

@Singleton
class DeviceToolRunner @Inject constructor(
    private val registry: ToolRegistry,
    private val context: ToolContext,
    private val json: Json,
) : ToolRunner {

    override suspend fun run(call: ToolCall, run: ToolRunContext): ToolResult {
        val tool = registry.tool(call.name)
            ?: return ToolResult.Failure("Unknown tool '${call.name}'. Use one of: ${registry.toolNames.joinToString()}")
        val cacheKey = if (tool.spec.cacheable) {
            call.name + "|" + call.argumentsJson.trim()
        } else {
            null
        }
        if (cacheKey != null) {
            val cached = run.cache[cacheKey] as? ToolResult.Success
            if (cached != null) {
                return ToolResult.Success("[cached from earlier in this run]\n" + cached.output)
            }
        }
        val arguments = ArgumentHealer.parse(call.argumentsJson) ?: JsonObject(emptyMap())
        val healed = ArgumentHealer.heal(arguments, tool.spec.parametersJson)
        val result = try {
            tool.execute(healed.arguments, context.copy(run = run))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Failure("${call.name} crashed: ${e.message ?: e.javaClass.simpleName}")
        }
        if (cacheKey != null && result is ToolResult.Success) {
            run.cache[cacheKey] = result
        }
        return when {
            healed.notes.isEmpty() -> result
            result is ToolResult.Success ->
                ToolResult.Success("${result.output}\n[arguments auto-repaired: ${healed.notes.joinToString()}]")
            result is ToolResult.Failure ->
                ToolResult.Failure("${result.reason} (arguments auto-repaired: ${healed.notes.joinToString()})")
            else -> result
        }
    }
}
