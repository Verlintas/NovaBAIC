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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SkillArgsTest {

    @Test
    fun stringPlaceholdersAreSubstituted() {
        val rendered = SkillArgs.render(
            """{"subject":"周报 · {{week}}"}""",
            buildJsonObject { put("week", "10月第2周") },
        ).getOrThrow()
        assertEquals("周报 · 10月第2周", Json.parseToJsonElement(rendered).jsonObject["subject"]!!.jsonPrimitive.content)
    }

    @Test
    fun wholeTokenPlaceholdersKeepTheirJsonType() {
        val rendered = SkillArgs.render(
            """{"level":"{{level}}","flag":"{{on}}","q":"{{text}}"}""",
            buildJsonObject {
                put("level", 5)
                put("on", true)
                put("text", "x")
            },
        ).getOrThrow()
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(5, obj["level"]!!.jsonPrimitive.int)
        assertTrue(obj["flag"]!!.jsonPrimitive.boolean)
        assertEquals("x", obj["q"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingParametersFailWithTheirNames() {
        val result = SkillArgs.render(
            """{"city":"{{city}}","date":"{{date}}"}""",
            buildJsonObject { put("city", "北京") },
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("date"))
    }

    @Test
    fun recipesWithoutPlaceholdersPassThrough() {
        val recipe = """{"level":3}"""
        assertEquals(recipe, SkillArgs.render(recipe, buildJsonObject {}).getOrThrow())
    }

    @Test
    fun nonJsonRecipesAreReturnedUnchanged() {
        assertEquals("{not json", SkillArgs.render("{not json", buildJsonObject {}).getOrThrow())
    }
}
