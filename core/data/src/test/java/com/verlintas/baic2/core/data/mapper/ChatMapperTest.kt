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

package com.verlintas.baic2.core.data.mapper

import com.verlintas.baic2.core.data.db.AgentEntity
import com.verlintas.baic2.core.data.db.ConversationEntity
import com.verlintas.baic2.core.data.db.MessageEntity
import com.verlintas.baic2.core.model.Agent
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.ProviderId
import com.verlintas.baic2.core.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ChatMapperTest {

    private val mapper = ChatMapper(Json { ignoreUnknownKeys = true; explicitNulls = false })

    @Test
    fun messageRoundTripPreservesToolCalls() {
        val message = ChatMessage(
            id = 7,
            conversationId = 3,
            role = ChatRole.ASSISTANT,
            content = "checking",
            thinking = "hmm",
            toolCalls = listOf(
                ToolCall(id = "call_1", name = "get_weather", argumentsJson = """{"city":"SF"}"""),
            ),
            model = "test-model",
            createdAt = 123L,
            starred = true,
        )

        val entity = mapper.messageToEntity(message)
        val restored = mapper.messageToModel(entity)

        assertEquals(message, restored)
        assertTrue(restored.starred)
    }

    @Test
    fun corruptedToolCallsJsonDegradesToEmptyList() {
        val entity = MessageEntity(
            id = 1,
            conversationId = 1,
            role = "ASSISTANT",
            content = "hi",
            thinking = null,
            toolCallsJson = "{not json",
            toolCallId = null,
            toolName = null,
            model = null,
            createdAt = 0,
        )

        val message = mapper.messageToModel(entity)

        assertTrue(message.toolCalls.isEmpty())
        assertEquals("hi", message.content)
    }

    @Test
    fun attachmentsRoundTripWithoutBase64() {
        val message = ChatMessage(
            id = 11,
            conversationId = 2,
            role = ChatRole.USER,
            content = "look",
            attachments = listOf(
                com.verlintas.baic2.core.model.Attachment(
                    id = "a1",
                    kind = com.verlintas.baic2.core.model.AttachmentKind.IMAGE,
                    mimeType = "image/jpeg",
                    localPath = "/data/attachments/a1.jpg",
                    base64 = "SHOULD_NOT_PERSIST",
                    sizeBytes = 123,
                ),
                com.verlintas.baic2.core.model.Attachment(
                    id = "t1",
                    kind = com.verlintas.baic2.core.model.AttachmentKind.TEXT,
                    mimeType = "text/plain",
                    fileName = "notes.txt",
                    text = "hello",
                ),
            ),
        )

        val entity = mapper.messageToEntity(message)
        val restored = mapper.messageToModel(entity)

        assertEquals(2, restored.attachments.size)
        assertEquals("/data/attachments/a1.jpg", restored.attachments[0].localPath)
        assertEquals(null, restored.attachments[0].base64)
        assertEquals("hello", restored.attachments[1].text)
    }

    @Test
    fun unknownModeIdIsPreservedVerbatim() {
        val entity = ConversationEntity(
            id = 1,
            title = "t",
            agentId = null,
            mode = "FUTURE_MODE",
            createdAt = 0,
            updatedAt = 0,
        )

        // Mode ids pass through; ModeProfileStore.resolve degrades unknown ones.
        assertEquals("FUTURE_MODE", mapper.conversationToModel(entity).mode)
    }

    @Test
    fun agentRoundTripKeepsProvider() {
        val entity = AgentEntity(
            id = 9,
            name = "DeepSeek",
            provider = "OPENAI_COMPATIBLE",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            temperature = 0.7,
            maxTokens = 4096,
            reasoning = true,
            systemPrompt = "be brief",
            encryptedApiKey = "v1.a.b",
            isDefault = true,
            createdAt = 1,
        )

        val model = mapper.agentToModel(entity)
        val back = mapper.agentToEntity(model, encryptedApiKey = entity.encryptedApiKey, createdAt = entity.createdAt)

        assertEquals(ProviderId.OPENAI_COMPATIBLE, model.provider)
        assertEquals(entity, back)
    }
}
