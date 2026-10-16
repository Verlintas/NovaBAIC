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

import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class GetTimeTool : DeviceTool {
    override val spec = ToolSpec(
        name = "get_time",
        description = "Current local date, time, weekday and timezone of the device.",
        parametersJson = """{"type":"object","properties":{}}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(now)
        val weekday = SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
        val zone = java.util.TimeZone.getDefault()
        return ToolResult.Success("$date $weekday (${zone.id}, UTC${zone.rawOffset / 3_600_000.0}h)")
    }
}

class ComputeTool : DeviceTool {
    override val spec = ToolSpec(
        name = "compute",
        description = "Evaluate a math expression deterministically (+, -, *, /, %, ^, parentheses, decimals). " +
            "Use this instead of doing arithmetic yourself.",
        parametersJson = """{"type":"object","properties":{"expression":{"type":"string","description":"e.g. (12+5)*3"}},"required":["expression"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val expression = (arguments["expression"] as? JsonPrimitive)?.content
            ?: return ToolResult.Failure("Missing 'expression' argument")
        return try {
            val result = ExpressionEvaluator(expression).evaluate()
            val rendered = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                result.toString()
            }
            ToolResult.Success("$expression = $rendered")
        } catch (e: ArithmeticException) {
            ToolResult.Failure("Math error: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ToolResult.Failure("Invalid expression: ${e.message}")
        }
    }
}

/** Tiny recursive-descent evaluator; no eval, no code execution. */
internal class ExpressionEvaluator(private val source: String) {
    private var position = 0

    fun evaluate(): Double {
        val value = parseExpression()
        skipSpaces()
        require(position >= source.length) { "unexpected '${source[position]}'" }
        return value
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (true) {
            skipSpaces()
            when {
                consume('+') -> value += parseTerm()
                consume('-') -> value -= parseTerm()
                else -> return value
            }
        }
    }

    private fun parseTerm(): Double {
        var value = parseUnary()
        while (true) {
            skipSpaces()
            when {
                consume('*') -> value *= parseUnary()
                consume('/') -> {
                    val divisor = parseUnary()
                    if (divisor == 0.0) throw ArithmeticException("division by zero")
                    value /= divisor
                }
                consume('%') -> {
                    val divisor = parseUnary()
                    if (divisor == 0.0) throw ArithmeticException("modulo by zero")
                    value %= divisor
                }
                else -> return value
            }
        }
    }

    private fun parsePower(): Double {
        val base = parseAtom()
        skipSpaces()
        if (consume('^')) {
            // Right-associative; the exponent may carry its own sign (2^-3).
            return Math.pow(base, parseUnary())
        }
        return base
    }

    private fun parseUnary(): Double {
        skipSpaces()
        if (consume('-')) return -parseUnary()
        if (consume('+')) return parseUnary()
        return parsePower()
    }

    private fun parseAtom(): Double {
        skipSpaces()
        if (consume('(')) {
            val value = parseExpression()
            skipSpaces()
            require(consume(')')) { "missing ')'" }
            return value
        }
        val start = position
        while (position < source.length && (source[position].isDigit() || source[position] == '.')) {
            position++
        }
        require(position > start) { "expected a number at index $start" }
        return source.substring(start, position).toDoubleOrNull()
            ?: throw IllegalArgumentException("bad number '${source.substring(start, position)}'")
    }

    private fun consume(char: Char): Boolean {
        skipSpaces()
        if (position < source.length && source[position] == char) {
            position++
            return true
        }
        return false
    }

    private fun skipSpaces() {
        while (position < source.length && source[position].isWhitespace()) position++
    }
}
