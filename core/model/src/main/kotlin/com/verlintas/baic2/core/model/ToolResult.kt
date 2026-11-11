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

/**
 * Result of a single tool invocation.
 *
 * Failures always carry an actionable reason that is written for the model:
 * a failure with a next step ends retry loops, a bare failure starts them.
 */
@Serializable
sealed interface ToolResult {

    @Serializable
    data class Success(
        val output: String,
        val images: List<ToolImage> = emptyList(),
    ) : ToolResult

    @Serializable
    data class Failure(
        val reason: String,
        val recoverable: Boolean = true,
    ) : ToolResult

    @Serializable
    data class Denied(val reason: String) : ToolResult
}

/** One image produced by a tool for the next multimodal model round. */
@Serializable
data class ToolImage(
    val mimeType: String,
    val base64: String,
    val fileName: String? = null,
    val localPath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)
