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

/**
 * Best-effort vision capability for the model names users actually configure.
 * Unknown local/self-hosted models are treated as text-only unless the tool
 * call explicitly asks for an image.
 */
object VisionModels {

    fun supports(model: String?): Boolean {
        val id = model?.trim()?.lowercase()?.substringAfterLast('/') ?: return false
        if (id.isBlank()) return false
        return id.startsWith("gpt-4o") ||
            id.startsWith("gpt-4.1") ||
            id.startsWith("gpt-5") ||
            id.startsWith("gpt-6") ||
            id.startsWith("claude-3") ||
            id.startsWith("claude-4") ||
            id.startsWith("claude-5") ||
            id.startsWith("gemini") ||
            id.startsWith("grok-4") ||
            id.startsWith("grok-2-vision") ||
            id.startsWith("qwen-vl") ||
            id.startsWith("qwen2.5-vl") ||
            id.startsWith("qwen3-vl") ||
            id.startsWith("glm-4v") ||
            id.startsWith("glm-4.5v") ||
            id.startsWith("llava") ||
            id.startsWith("pixtral") ||
            id.startsWith("minicpm-v") ||
            id.startsWith("moondream") ||
            // Covers names such as llama-3.2-11b-vision-instruct.
            (id.startsWith("llama-3.2") && id.contains("vision")) ||
            id.contains("-vision")
    }
}
