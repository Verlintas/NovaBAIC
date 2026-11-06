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

package com.verlintas.baic2.core.network.provider.openai

import com.verlintas.baic2.core.model.ChatProvider
import com.verlintas.baic2.core.model.ChatRequest
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.core.model.ProviderError
import com.verlintas.baic2.core.model.StreamEvent
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.network.provider.BODY_LIMIT
import com.verlintas.baic2.core.network.provider.ProviderException
import com.verlintas.baic2.core.network.provider.ToolCallHistory
import com.verlintas.baic2.core.network.provider.executeWithRetry
import com.verlintas.baic2.core.network.provider.mapHttpError
import com.verlintas.baic2.core.network.provider.mapIOException
import com.verlintas.baic2.core.network.sse.SseParser
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OpenAI Chat Completions compatible adapter (OpenAI, DeepSeek, Moonshot,
 * Qwen, Ollama, gateways...).
 *
 * Blocking IO: collect this flow on [kotlinx.coroutines.Dispatchers.IO].
 * Cancelling the collector cancels the in-flight HTTP call.
 */
class OpenAiCompatibleProvider(
    private val client: OkHttpClient,
    private val json: Json,
) : ChatProvider {

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val job = currentCoroutineContext()[Job]
        var currentCall: Call? = null
        val cancelCall = job?.invokeOnCompletion { currentCall?.cancel() }

        try {
            val (call, response) = client.executeWithRetry { buildHttpRequest(request) }
            currentCall = call

            val body = response.body
            if (!response.isSuccessful || body == null) {
                val errorBody = runCatching { body?.string() }.getOrNull().orEmpty().take(BODY_LIMIT)
                emit(
                    StreamEvent.Failed(
                        mapHttpError(
                            code = response.code,
                            retryAfterHeader = response.header("Retry-After"),
                            message = extractErrorMessage(errorBody),
                        ),
                    ),
                )
                return@flow
            }

            val parser = SseParser()
            val toolCalls = LinkedHashMap<Int, ToolCallAccumulator>()

            body.source().use { source ->
                while (true) {
                    val line = try {
                        source.readUtf8Line() ?: break
                    } catch (e: IOException) {
                        if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                        emit(StreamEvent.Failed(mapIOException(e)))
                        return@flow
                    }
                    val event = parser.line(line) ?: continue
                    if (processEvent(event.data, toolCalls)) break
                }
                parser.endOfStream()?.let { processEvent(it.data, toolCalls) }
            }

            val calls = toolCalls.values.map { it.toToolCall() }
            if (calls.isNotEmpty()) emit(StreamEvent.ToolCallsDone(calls))
            emit(StreamEvent.Done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
            emit(StreamEvent.Failed(mapIOException(e)))
        } finally {
            cancelCall?.dispose()
            currentCall?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(joinUrl(config.baseUrl, "models"))
            .auth(config.apiKey)
            .get()
            .build()
        val call = client.newCall(request)
        val job = currentCoroutineContext()[Job]
        val cancelCall = job?.invokeOnCompletion { call.cancel() }
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val body = runCatching { response.body?.string() }.getOrNull().orEmpty().take(BODY_LIMIT)
                throw ProviderException(
                    mapHttpError(
                        code = response.code,
                        retryAfterHeader = response.header("Retry-After"),
                        message = extractErrorMessage(body),
                    ),
                )
            }
            val text = response.body?.string() ?: return@withContext emptyList()
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val data = root?.get("data") as? JsonArray ?: return@withContext emptyList()
            return@withContext data.mapNotNull { element ->
                ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ProviderException(mapIOException(e))
        } finally {
            cancelCall?.dispose()
            call.cancel()
        }
    }

    /** Emits deltas/usage directly; returns true when the stream is terminal. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.processEvent(
        data: String,
        toolCalls: MutableMap<Int, ToolCallAccumulator>,
    ): Boolean {
        if (data == DONE_MARKER) return true
        if (data.isBlank()) return false

        val chunk = runCatching { json.decodeFromString(OpenAiChunk.serializer(), data) }.getOrNull()
            ?: return false

        chunk.choices.firstOrNull()?.delta?.let { delta ->
            delta.content?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.TextDelta(it)) }
            delta.reasoning_content?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.ThinkingDelta(it)) }
            delta.tool_calls?.forEach { fragment ->
                val acc = toolCalls.getOrPut(fragment.index) { ToolCallAccumulator() }
                fragment.id?.let { acc.id = it }
                fragment.function?.name?.let { acc.name = it }
                fragment.function?.arguments?.let { acc.arguments.append(it) }
            }
        }
        chunk.usage?.let { emit(StreamEvent.Usage(it.promptTokens, it.completionTokens)) }
        return false
    }

    /** A blank key means a keyless local server: send no Authorization at all. */
    private fun Request.Builder.auth(apiKey: String): Request.Builder =
        if (apiKey.isBlank()) this else header("Authorization", "Bearer $apiKey")

    private fun buildHttpRequest(request: ChatRequest): Request {
        val body = json.encodeToString(WireRequest.serializer(), buildPayload(request))
        return Request.Builder()
            .url(joinUrl(request.config.baseUrl, "chat/completions"))
            .auth(request.config.apiKey)
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildPayload(request: ChatRequest): WireRequest {
        val model = canonicalModel(request.config.model)
        val normalizedModel = model.lowercase()
        val openAiReasoningFamily = normalizedModel.startsWith("o1") ||
            normalizedModel.startsWith("o3") || normalizedModel.startsWith("o4") ||
            normalizedModel.startsWith("gpt-5") || normalizedModel.startsWith("gpt-6")
        // Native DeepSeek ids only; gateways use names like "deepseek-ai/…".
        val deepSeek = normalizedModel.startsWith("deepseek-") && !normalizedModel.contains("/")
        // GLM-5.x reasoning is on by default; 5.3 rejects "disabled" outright.
        val glmReasoning = normalizedModel.startsWith("glm-5") && !normalizedModel.contains("/")
        // Kimi K3 always thinks; only the effort level can be tuned.
        val kimiK3 = normalizedModel.startsWith("kimi-k3") && !normalizedModel.contains("/")
        val messages = buildList {
            if (request.systemPrompt.isNotBlank()) {
                add(WireMessage(role = "system", content = JsonPrimitive(request.systemPrompt)))
            }
            ToolCallHistory.sanitize(request.messages).forEach { message ->
                when (message.role) {
                    ChatRole.SYSTEM -> add(
                        WireMessage(role = "system", content = JsonPrimitive(message.content)),
                    )
                    ChatRole.USER -> add(WireMessage(role = "user", content = buildUserContent(message)))
                    ChatRole.TOOL -> add(
                        WireMessage(
                            role = "tool",
                            content = JsonPrimitive(message.content),
                            toolCallId = message.toolCallId,
                        ),
                    )
                    ChatRole.ASSISTANT -> add(
                        WireMessage(
                            role = "assistant",
                            content = message.content
                                .takeIf { it.isNotBlank() }
                                ?.let { JsonPrimitive(it) },
                            toolCalls = message.toolCalls
                                .takeIf { it.isNotEmpty() }
                                ?.map { call ->
                                    WireToolCall(
                                        id = call.id,
                                        function = WireFunction(call.name, call.argumentsJson),
                                    )
                                },
                            // DeepSeek V4 and Kimi K3 require previous thinking
                            // back once the request carries tools.
                            reasoningContent = message.thinking.takeIf {
                                (deepSeek && request.config.reasoning) || kimiK3
                            },
                        ),
                    )
                }
            }
        }
        val tools = request.tools.takeIf { it.isNotEmpty() }?.map { spec ->
            WireTool(
                function = WireToolDef(
                    name = spec.name,
                    description = spec.description,
                    parameters = parseParameters(spec.parametersJson),
                ),
            )
        }
        return WireRequest(
            model = model,
            // Thinking modes ignore a temperature; OpenAI reasoning models
            // reject one outright; Kimi K3 pins its sampling values.
            temperature = if (request.config.reasoning || kimiK3) {
                null
            } else {
                request.config.temperature
            },
            // o-series and GPT-5/6 reject max_tokens; they use the newer name.
            maxTokens = if (openAiReasoningFamily) null else request.config.maxTokens,
            maxCompletionTokens = if (openAiReasoningFamily) request.config.maxTokens else null,
            messages = messages,
            tools = tools,
            streamOptions = WireStreamOptions(),
            // Only these families accept this field; other compatible
            // endpoints (Qwen, gateways…) would reject it.
            reasoningEffort = when {
                openAiReasoningFamily || deepSeek ->
                    if (request.config.reasoning) "high" else null
                glmReasoning || kimiK3 ->
                    if (request.config.reasoning) "high" else "low"
                else -> null
            },
            // DeepSeek thinking defaults to on and can be switched off; GLM-5
            // thinking cannot be disabled, so reasoning-off maps to low effort.
            thinking = when {
                deepSeek -> WireThinking(if (request.config.reasoning) "enabled" else "disabled")
                glmReasoning -> WireThinking("enabled")
                else -> null
            },
        )
    }

    /**
     * DeepSeek retired `deepseek-chat`/`deepseek-reasoner` on 2026-07-24;
     * route stored agent configs to V4.1-Flash instead of failing.
     */
    private fun canonicalModel(model: String): String = when (model.lowercase()) {
        "deepseek-chat", "deepseek-reasoner" -> "deepseek-flash"
        else -> model
    }

    /**
     * Plain user messages stay a JSON string; messages with images become the
     * OpenAI content-parts array (text + data URLs).
     */
    private fun buildUserContent(message: com.verlintas.baic2.core.model.ChatMessage): JsonElement? {
        val textContent = buildTextWithAttachments(message)
        val images = message.attachments.filter {
            it.kind == com.verlintas.baic2.core.model.AttachmentKind.IMAGE && it.base64 != null
        }
        if (images.isEmpty()) {
            return JsonPrimitive(textContent)
        }
        return JsonArray(
            buildList {
                if (textContent.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", textContent)
                        },
                    )
                }
                images.forEach { image ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put(
                                "image_url",
                                buildJsonObject {
                                    put("url", "data:${image.mimeType};base64,${image.base64}")
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    private fun buildTextWithAttachments(message: com.verlintas.baic2.core.model.ChatMessage): String =
        buildString {
            append(message.content)
            message.attachments
                .filter { it.kind == com.verlintas.baic2.core.model.AttachmentKind.TEXT }
                .forEach { attachment ->
                    attachment.text?.let { text ->
                        if (isNotBlank()) append("\n\n")
                        append("[附件: ").append(attachment.fileName ?: "text").append("]\n")
                        append(text)
                    }
                }
        }.trim()

    private fun parseParameters(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull()
            ?.takeIf { it["type"] != null }
            ?: PERMISSIVE_TOOL_SCHEMA

    private fun extractErrorMessage(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val error = root["error"] as? JsonObject ?: return null
        (error["message"] as? JsonPrimitive)?.content
    }.getOrNull()

    private fun joinUrl(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        fun toToolCall(): ToolCall = ToolCall(
            id = id.ifEmpty { "call_${name}_${arguments.length}" },
            name = name,
            argumentsJson = arguments.toString().ifBlank { "{}" },
        )
    }

    private companion object {
        const val DONE_MARKER = "[DONE]"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class WireRequest(
    val model: String,
    @EncodeDefault val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val messages: List<WireMessage>,
    val tools: List<WireTool>? = null,
    @SerialName("stream_options") val streamOptions: WireStreamOptions? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val thinking: WireThinking? = null,
)

@Serializable
private data class WireThinking(
    val type: String,
)

@Serializable
private data class WireStreamOptions(
    @SerialName("include_usage") @EncodeDefault val includeUsage: Boolean = true,
)

@Serializable
private data class WireMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
private data class WireToolCall(
    val id: String,
    @EncodeDefault val type: String = "function",
    val function: WireFunction,
)

@Serializable
private data class WireFunction(
    val name: String,
    val arguments: String,
)

@Serializable
private data class WireTool(
    @EncodeDefault val type: String = "function",
    val function: WireToolDef,
)

@Serializable
private data class WireToolDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
private data class OpenAiChunk(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        val content: String? = null,
        @SerialName("reasoning_content") val reasoning_content: String? = null,
        @SerialName("tool_calls") val tool_calls: List<ToolCallDelta>? = null,
    )

    @Serializable
    data class ToolCallDelta(
        val index: Int = 0,
        val id: String? = null,
        val function: FunctionDelta? = null,
    ) {
        @Serializable
        data class FunctionDelta(
            val name: String? = null,
            val arguments: String? = null,
        )
    }

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Long? = null,
        @SerialName("completion_tokens") val completionTokens: Long? = null,
    )
}

/**
 * Tools with a broken schema must never poison the whole request: fall back
 * to a permissive object schema the APIs accept.
 */
private val PERMISSIVE_TOOL_SCHEMA: JsonObject = Json.parseToJsonElement(
    """{"type":"object","properties":{}}""",
) as JsonObject
