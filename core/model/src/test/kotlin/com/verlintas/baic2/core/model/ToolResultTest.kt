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

package com.verlintas.baic2.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolResultTest {

    private val json = Json

    @Test
    fun failureDefaultsToRecoverable() {
        val result = ToolResult.Failure("missing contacts permission")
        assertTrue(result.recoverable)
    }

    @Test
    fun serializationRoundTripPreservesSealedVariant() {
        val original: ToolResult = ToolResult.Failure("boom", recoverable = false)

        val decoded = json.decodeFromString<ToolResult>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun deniedRoundTrip() {
        val original: ToolResult = ToolResult.Denied("Plan mode forbids write tools")

        val decoded = json.decodeFromString<ToolResult>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun successWithImagesRoundTrip() {
        val original: ToolResult = ToolResult.Success(
            "Screenshot captured",
            images = listOf(
                ToolImage(
                    mimeType = "image/png",
                    base64 = "aGVsbG8=",
                    fileName = "web.png",
                    width = 720,
                    height = 1600,
                ),
            ),
        )

        val decoded = json.decodeFromString<ToolResult>(json.encodeToString(original))

        assertEquals(original, decoded)
    }
}
