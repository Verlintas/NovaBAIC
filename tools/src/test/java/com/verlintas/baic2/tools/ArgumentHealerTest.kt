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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class ArgumentHealerTest {

    private val schema = """
        {
          "type": "object",
          "properties": {
            "city": {"type": "string"},
            "count": {"type": "integer"},
            "enabled": {"type": "boolean"},
            "tags": {"type": "array"}
          }
        }
    """.trimIndent()

    @Test
    fun parsesPlainObject() {
        val parsed = ArgumentHealer.parse("""{"city":"Beijing"}""")
        assertEquals("Beijing", parsed?.get("city")?.jsonPrimitive?.content)
    }

    @Test
    fun parsesDoubleEncodedObject() {
        val parsed = ArgumentHealer.parse("\"{\\\"city\\\":\\\"Beijing\\\"}\"")
        assertEquals("Beijing", parsed?.get("city")?.jsonPrimitive?.content)
    }

    @Test
    fun parsesProseWrappedObject() {
        val parsed = ArgumentHealer.parse("Sure! {\"city\":\"Beijing\"} hope that helps")
        assertEquals("Beijing", parsed?.get("city")?.jsonPrimitive?.content)
    }

    @Test
    fun renamesMisspelledKeys() {
        val healed = ArgumentHealer.heal(JsonObject(mapOf("citty" to JsonPrimitive("Beijing"))), schema)
        assertEquals("Beijing", healed.arguments["city"]?.jsonPrimitive?.content)
        assertTrue(healed.notes.isNotEmpty())
    }

    @Test
    fun coercesPrimitiveTypes() {
        val healed = ArgumentHealer.heal(
            JsonObject(
                mapOf(
                    "count" to JsonPrimitive("5"),
                    "enabled" to JsonPrimitive("true"),
                    "tags" to JsonPrimitive("solo"),
                ),
            ),
            schema,
        )
        assertEquals(5L, healed.arguments["count"]?.jsonPrimitive?.content?.toLong())
        assertEquals(true, healed.arguments["enabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(1, healed.arguments["tags"]?.jsonArray?.size)
    }

    @Test
    fun fixesCaseOnlyKeyMismatches() {
        val healed = ArgumentHealer.heal(JsonObject(mapOf("City" to JsonPrimitive("Beijing"))), schema)
        assertEquals("Beijing", healed.arguments["city"]?.jsonPrimitive?.content)
        assertTrue(healed.notes.isNotEmpty())
    }

    @Test
    fun skipsAmbiguousRenames() {
        val ambiguousSchema = """
            {"type":"object","properties":{"path":{"type":"string"},"pash":{"type":"string"}}}
        """.trimIndent()
        val healed = ArgumentHealer.heal(
            JsonObject(mapOf("pafh" to JsonPrimitive("x"))),
            ambiguousSchema,
        )
        assertEquals("x", healed.arguments["pafh"]?.jsonPrimitive?.content)
        assertTrue(healed.notes.isEmpty())
    }

    @Test
    fun leavesValidArgumentsUntouched() {
        val arguments = JsonObject(
            mapOf(
                "city" to JsonPrimitive("Beijing"),
                "count" to JsonPrimitive(3),
                "enabled" to JsonPrimitive(true),
            ),
        )
        val healed = ArgumentHealer.heal(arguments, schema)
        assertEquals(arguments, healed.arguments)
        assertTrue(healed.notes.isEmpty())
    }

    @Test
    fun returnsEmptyObjectForBlankInput() {
        assertEquals(JsonObject(emptyMap()), ArgumentHealer.parse("  "))
        assertEquals(null, ArgumentHealer.parse("not json at all"))
    }
}
