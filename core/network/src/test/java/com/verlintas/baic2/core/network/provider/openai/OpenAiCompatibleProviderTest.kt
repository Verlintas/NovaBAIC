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

import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRequest
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.core.model.ProviderError
import com.verlintas.baic2.core.model.ProviderId
import com.verlintas.baic2.core.model.StreamEvent
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.core.network.provider.ProviderException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleProviderTest {

    private lateinit var server: MockWebServer
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val provider = OpenAiCompatibleProvider(OkHttpClient(), json)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(reasoning: Boolean = false, model: String = "test-model") = ProviderConfig(
        provider = ProviderId.OPENAI_COMPATIBLE,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
        model = model,
        reasoning = reasoning,
    )

    private fun request(
        messages: List<ChatMessage> = listOf(ChatMessage(role = ChatRole.USER, content = "hi")),
        tools: List<ToolSpec> = emptyList(),
        reasoning: Boolean = false,
        model: String = "test-model",
    ) = ChatRequest(
        config = config(reasoning, model),
        systemPrompt = "be nice",
        messages = messages,
        tools = tools,
    )

    private fun sseBody(vararg payloads: String): String = buildString {
        payloads.forEach { payload ->
            append("data: ").append(payload).append("\n\n")
        }
    }

    private fun enqueueSse(vararg payloads: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody(*payloads)),
        )
    }

    @Test
    fun streamsTextDeltasAndDone() = runTest {
        enqueueSse(
            """{"choices":[{"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"delta":{"content":"lo"}}]}""",
            "[DONE]",
        )

        val events = provider.stream(request()).toList()

        assertEquals(
            listOf(
                StreamEvent.TextDelta("Hel"),
                StreamEvent.TextDelta("lo"),
                StreamEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun emitsThinkingAndUsage() = runTest {
        enqueueSse(
            """{"choices":[{"delta":{"reasoning_content":"hmm"}}]}""",
            """{"choices":[{"delta":{"content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
            "[DONE]",
        )

        val events = provider.stream(request()).toList()

        assertEquals(
            listOf(
                StreamEvent.ThinkingDelta("hmm"),
                StreamEvent.TextDelta("ok"),
                StreamEvent.Usage(10, 2),
                StreamEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun assemblesToolCallsFromFragments() = runTest {
        enqueueSse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":"{\"ci"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"SF\"}"}}]}}]}""",
            "[DONE]",
        )

        val events = provider.stream(request()).toList()
        val toolCalls = events.filterIsInstance<StreamEvent.ToolCallsDone>().single()

        assertEquals(1, toolCalls.calls.size)
        assertEquals("call_1", toolCalls.calls[0].id)
        assertEquals("get_weather", toolCalls.calls[0].name)
        assertEquals("""{"city":"SF"}""", toolCalls.calls[0].argumentsJson)
        assertEquals(StreamEvent.Done, events.last())
    }

    @Test
    fun mapsAuthError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"bad key"}}"""),
        )

        val events = provider.stream(request()).toList()
        val failure = events.filterIsInstance<StreamEvent.Failed>().single()

        assertEquals(ProviderError.Kind.AUTH, failure.error.kind)
        assertEquals("bad key", failure.error.message)
        assertEquals(401, failure.error.httpStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesServerErrorOnceThenSucceeds() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "0")
                .setBody("busy"),
        )
        enqueueSse("""{"choices":[{"delta":{"content":"ok"}}]}""", "[DONE]")

        val events = provider.stream(request()).toList()

        assertEquals(
            listOf(StreamEvent.TextDelta("ok"), StreamEvent.Done),
            events,
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun buildsRequestWithSystemPromptHistoryAndTools() = runTest {
        enqueueSse("[DONE]")
        val tools = listOf(
            ToolSpec(
                name = "get_weather",
                description = "weather lookup",
                parametersJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
            ),
        )

        provider.stream(request(tools = tools)).toList()

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))

        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        assertTrue(body.getValue("stream").jsonPrimitive.content == "true")

        val messages = body.getValue("messages").jsonArray
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("be nice", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.content)

        val streamOptions = body.getValue("stream_options").jsonObject
        assertTrue(streamOptions.getValue("include_usage").jsonPrimitive.content == "true")

        val wireTools = body.getValue("tools").jsonArray
        val function = wireTools[0].jsonObject.getValue("function").jsonObject
        assertEquals("get_weather", function.getValue("name").jsonPrimitive.content)
        val params = function.getValue("parameters") as JsonObject
        assertEquals(
            "string",
            params.getValue("properties").jsonObject
                .getValue("city").jsonObject
                .getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun repairsDanglingToolCallsBeforeSending() = runTest {
        enqueueSse("[DONE]")
        val history = listOf(
            ChatMessage(role = ChatRole.USER, content = "weather?"),
            ChatMessage(
                id = 2,
                conversationId = 1,
                role = ChatRole.ASSISTANT,
                content = "checking",
                toolCalls = listOf(
                    com.verlintas.baic2.core.model.ToolCall(
                        id = "call_1",
                        name = "get_weather",
                        argumentsJson = "{}",
                        result = "sunny",
                    ),
                ),
                createdAt = 1_000,
            ),
            ChatMessage(role = ChatRole.USER, content = "and tomorrow?"),
        )

        provider.stream(request(messages = history)).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body.getValue("messages").jsonArray
        assertEquals(
            listOf("system", "user", "assistant", "tool", "user"),
            messages.map { it.jsonObject.getValue("role").jsonPrimitive.content },
        )
        val tool = messages[3].jsonObject
        assertEquals("call_1", tool.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("sunny", tool.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun listModelsParsesIds() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"a"},{"id":"b"},{"id":""}]}"""),
        )

        val models = provider.listModels(config())

        assertEquals(listOf("a", "b"), models)
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun listModelsThrowsTypedError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"nope"}}"""),
        )

        val exception = assertFailsWith<ProviderException> {
            provider.listModels(config())
        }

        assertEquals(ProviderError.Kind.AUTH, exception.error.kind)
    }

    @Test
    fun blankApiKeySendsNoAuthorizationHeader() = runTest {
        enqueueSse("[DONE]")

        provider.stream(request().copy(config = config().copy(apiKey = ""))).toList()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun blankApiKeyStillListsModels() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"a"}]}"""),
        )

        val models = provider.listModels(config().copy(apiKey = ""))

        assertEquals(listOf("a"), models)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun unknownJsonFieldsAreIgnored() = runTest {
        enqueueSse(
            """{"choices":[{"delta":{"content":"x"},"future_field":1}],"vendor_extra":true}""",
            "[DONE]",
        )

        val events = provider.stream(request()).toList()

        assertEquals(listOf(StreamEvent.TextDelta("x"), StreamEvent.Done), events)
    }

    @Test
    fun mapsImageAttachmentToContentParts() = runTest {
        enqueueSse("[DONE]")
        val history = listOf(
            ChatMessage(
                role = ChatRole.USER,
                content = "what is this?",
                attachments = listOf(
                    com.verlintas.baic2.core.model.Attachment(
                        id = "a1",
                        kind = com.verlintas.baic2.core.model.AttachmentKind.IMAGE,
                        mimeType = "image/jpeg",
                        base64 = "QUJD",
                    ),
                ),
            ),
        )

        provider.stream(request(messages = history)).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val content = body.getValue("messages").jsonArray[1].jsonObject.getValue("content")
        val parts = content as kotlinx.serialization.json.JsonArray
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            parts[1].jsonObject.getValue("image_url").jsonObject
                .getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun mapsTextAttachmentIntoThePrompt() = runTest {
        enqueueSse("[DONE]")
        val history = listOf(
            ChatMessage(
                role = ChatRole.USER,
                content = "check this",
                attachments = listOf(
                    com.verlintas.baic2.core.model.Attachment(
                        id = "t1",
                        kind = com.verlintas.baic2.core.model.AttachmentKind.TEXT,
                        mimeType = "text/plain",
                        fileName = "notes.txt",
                        text = "hello attachment",
                    ),
                ),
            ),
        )

        provider.stream(request(messages = history)).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val content = body.getValue("messages").jsonArray[1].jsonObject
            .getValue("content").jsonPrimitive.content
        assertTrue(content.contains("check this"))
        assertTrue(content.contains("[附件: notes.txt]"))
        assertTrue(content.contains("hello attachment"))
    }

    @Test
    fun assistantToolCallsAreMappedOnTheWire() = runTest {
        enqueueSse("[DONE]")
        val history = listOf(
            ChatMessage(role = ChatRole.USER, content = "weather?"),
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    com.verlintas.baic2.core.model.ToolCall(
                        id = "call_9",
                        name = "get_weather",
                        argumentsJson = """{"city":"SF"}""",
                    ),
                ),
            ),
            ChatMessage(
                role = ChatRole.TOOL,
                content = """{"temp":18}""",
                toolCallId = "call_9",
                toolName = "get_weather",
            ),
        )

        provider.stream(request(messages = history)).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body.getValue("messages").jsonArray
        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant.getValue("role").jsonPrimitive.content)
        val toolCall = assistant.getValue("tool_calls").jsonArray[0].jsonObject
        assertEquals("call_9", toolCall.getValue("id").jsonPrimitive.content)
        assertEquals(
            "get_weather",
            toolCall.getValue("function").jsonObject.getValue("name").jsonPrimitive.content,
        )
        val toolResult = messages[3].jsonObject
        assertEquals("tool", toolResult.getValue("role").jsonPrimitive.content)
        assertEquals("call_9", toolResult.getValue("tool_call_id").jsonPrimitive.content)
    }

    @Test
    fun omitsTemperatureWhileReasoning() = runTest {
        enqueueSse("[DONE]")

        provider.stream(request(reasoning = true)).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(
            body["temperature"] == null ||
                body["temperature"] is kotlinx.serialization.json.JsonNull,
            "reasoning models reject an explicit temperature",
        )
    }

    @Test
    fun repairsBrokenToolSchemas() = runTest {
        enqueueSse("[DONE]")
        val tools = listOf(
            ToolSpec(name = "broken", description = "x", parametersJson = "{\"type\":\"object\""),
        )

        provider.stream(request(tools = tools)).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val function = body.getValue("tools").jsonArray[0].jsonObject.getValue("function").jsonObject
        val parameters = function.getValue("parameters").jsonObject
        assertEquals("object", parameters.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun sendsReasoningEffortOnlyForOpenAiReasoningFamilies() = runTest {
        enqueueSse("[DONE]")
        provider.stream(request(reasoning = true, model = "o3-mini")).toList()
        val reasoningBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "high",
            reasoningBody.getValue("reasoning_effort").jsonPrimitive.content,
        )

        enqueueSse("[DONE]")
        provider.stream(request(reasoning = true, model = "qwen-plus")).toList()
        val otherBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(otherBody["reasoning_effort"] == null, "non-OpenAI models must not receive it")
        assertTrue(otherBody["thinking"] == null, "non-DeepSeek models must not receive it")
    }

    @Test
    fun controlsDeepSeekThinkingMode() = runTest {
        enqueueSse("[DONE]")
        provider.stream(request(reasoning = true, model = "deepseek-flash")).toList()
        val thinkingBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "high",
            thinkingBody.getValue("reasoning_effort").jsonPrimitive.content,
        )
        assertEquals(
            "enabled",
            thinkingBody.getValue("thinking").jsonObject
                .getValue("type").jsonPrimitive.content,
        )

        enqueueSse("[DONE]")
        provider.stream(request(reasoning = false, model = "deepseek-flash")).toList()
        val offBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(offBody["reasoning_effort"] == null)
        assertEquals(
            "disabled",
            offBody.getValue("thinking").jsonObject
                .getValue("type").jsonPrimitive.content,
            "DeepSeek thinking defaults to on and must be switched off explicitly",
        )
    }

    @Test
    fun keepsGlmThinkingEnabledAndLowersEffortWhenReasoningOff() = runTest {
        enqueueSse("[DONE]")
        provider.stream(request(reasoning = false, model = "glm-5.3")).toList()
        val offBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "enabled",
            offBody.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content,
            "GLM-5.3 rejects thinking.type=disabled",
        )
        assertEquals("low", offBody.getValue("reasoning_effort").jsonPrimitive.content)

        enqueueSse("[DONE]")
        provider.stream(request(reasoning = true, model = "glm-5.3")).toList()
        val onBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("high", onBody.getValue("reasoning_effort").jsonPrimitive.content)
    }

    @Test
    fun tunesKimiK3EffortWithoutThinkingToggle() = runTest {
        enqueueSse("[DONE]")
        provider.stream(request(reasoning = false, model = "kimi-k3")).toList()
        val offBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("low", offBody.getValue("reasoning_effort").jsonPrimitive.content)
        assertTrue(offBody["thinking"] == null, "K3 is always in thinking mode")
        assertTrue(offBody["temperature"] == null, "K3 pins its sampling values")

        enqueueSse("[DONE]")
        provider.stream(request(reasoning = true, model = "kimi-k3")).toList()
        val onBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("high", onBody.getValue("reasoning_effort").jsonPrimitive.content)
    }

    @Test
    fun kimiK3ReplaysThinkingOnTheWire() = runTest {
        enqueueSse("[DONE]")
        val history = listOf(
            ChatMessage(role = ChatRole.USER, content = "hi"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "ok", thinking = "let me think"),
        )

        provider.stream(request(messages = history, reasoning = false, model = "kimi-k3")).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val assistant = body.getValue("messages").jsonArray[2].jsonObject
        assertEquals(
            "let me think",
            assistant.getValue("reasoning_content").jsonPrimitive.content,
        )
    }

    @Test
    fun deepSeekReasoningRoundTripsWhenThinking() = runTest {
        enqueueSse("[DONE]")
        val history = listOf(
            ChatMessage(role = ChatRole.USER, content = "weather?"),
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "checking",
                thinking = "I should call the weather tool",
            ),
        )
        val tools = listOf(
            ToolSpec(name = "get_weather", description = "x", parametersJson = "{}"),
        )

        provider.stream(
            request(messages = history, tools = tools, reasoning = true, model = "deepseek-flash"),
        ).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val assistant = body.getValue("messages").jsonArray[2].jsonObject
        assertEquals(
            "I should call the weather tool",
            assistant.getValue("reasoning_content").jsonPrimitive.content,
        )
    }

    @Test
    fun deepSeekLegacyNamesAreRoutedToV41Flash() = runTest {
        enqueueSse("[DONE]")
        provider.stream(request(reasoning = false, model = "deepseek-chat")).toList()

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body.getValue("model").jsonPrimitive.content)
    }
}
