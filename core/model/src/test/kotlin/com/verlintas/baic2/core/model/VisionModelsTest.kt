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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisionModelsTest {

    @Test
    fun knownVisionFamiliesAreDetected() {
        assertTrue(VisionModels.supports("gpt-4o"))
        assertTrue(VisionModels.supports("gpt-4.1-mini"))
        assertTrue(VisionModels.supports("gpt-5.6-sol"))
        assertTrue(VisionModels.supports("claude-3-5-sonnet"))
        assertTrue(VisionModels.supports("claude-4-opus"))
        assertTrue(VisionModels.supports("gemini-2.5-pro"))
        assertTrue(VisionModels.supports("qwen2.5-vl-72b"))
        assertTrue(VisionModels.supports("llava:13b"))
        assertTrue(VisionModels.supports("llama-3.2-11b-vision-instruct"))
        assertTrue(VisionModels.supports("acme/some-vision-model"))
    }

    @Test
    fun textOnlyAndUnknownModelsAreNotAssumedToSee() {
        assertFalse(VisionModels.supports("deepseek-flash"))
        assertFalse(VisionModels.supports("deepseek-v4-pro"))
        assertFalse(VisionModels.supports("llama-3.1-70b"))
        assertFalse(VisionModels.supports("qwen2.5-72b"))
        assertFalse(VisionModels.supports(null))
        assertFalse(VisionModels.supports(""))
    }
}
