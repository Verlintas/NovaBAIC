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

import kotlinx.serialization.Serializable

@Serializable
data class SkillStep(
    val tool: String,
    val argsJson: String = "{}",
)

@Serializable
data class SkillToolDef(
    val id: String,
    val description: String,
    val steps: List<SkillStep>,
    /** JSON schema for the composite tool; steps may use {{name}} placeholders. */
    val parametersJson: String = """{"type":"object","properties":{}}""",
)

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val version: Int = 1,
    val description: String = "",
    val instructions: String = "",
    val permissions: List<String> = emptyList(),
    val tools: List<SkillToolDef> = emptyList(),
)
