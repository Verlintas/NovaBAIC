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

import com.verlintas.baic2.core.engine.QuestionQueue
import com.verlintas.baic2.core.engine.UserQuestion
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pauses the run and asks the person one question, returning their answer as
 * the tool result. When nobody is watching (or they dismiss it), the run
 * continues with a clear "no answer" instead of hanging.
 */
class AskUserTool(
    private val questions: QuestionQueue,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "ask_user",
        description = "Pause and ask the user one question when the decision is genuinely theirs: " +
            "an ambiguous target, a destructive choice, or a detail only they know. One question, " +
            "not an interview - prefer acting when you can proceed sensibly. Returns their answer, " +
            "or 'no answer' when they are away; then continue with your best judgment and say what " +
            "you assumed.",
        parametersJson = """{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"},"description":"optional quick choices, up to 4"}},"required":["question"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val question = (arguments["question"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (question.isEmpty()) return ToolResult.Failure("Missing 'question' argument")
        val options = (arguments["options"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .take(4)
        // Scheduled/automation runs have nobody watching: answer immediately.
        if (context.run.unattended) {
            return ToolResult.Success(
                "No answer is possible right now (this run is unattended). Continue with your " +
                    "best judgment and state the assumption you made.",
            )
        }
        val answer = questions.ask(
            UserQuestion(
                id = UUID.randomUUID().toString(),
                question = question.take(500),
                options = options,
                conversationId = context.run.conversationId,
            ),
        )
        return if (answer.isNullOrBlank()) {
            ToolResult.Success(
                "No answer was given (the user is away or busy). Continue with your best judgment " +
                    "and state the assumption you made.",
            )
        } else {
            ToolResult.Success("User answered: ${answer.trim().take(1_000)}")
        }
    }
}
