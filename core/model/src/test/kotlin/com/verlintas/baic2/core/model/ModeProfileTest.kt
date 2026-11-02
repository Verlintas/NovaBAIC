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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModeProfileTest {

    @Test
    fun engineModeMapsPermissionsToClass() {
        assertEquals(
            AppMode.CHAT,
            ModeProfiles.CHAT.engineMode,
        )
        assertEquals(
            AppMode.CHAT_PLUS,
            ModeProfiles.CHAT_PLUS.engineMode,
        )
        assertEquals(
            AppMode.ACT,
            ModeProfiles.ACT.engineMode,
        )
        assertEquals(
            AppMode.MAX,
            ModeProfiles.MAX.engineMode,
        )
    }

    @Test
    fun customModesMapToTheSameFourClasses() {
        val custom = ModeProfile(
            id = "custom-1",
            name = "Night watch",
            toolAccess = ToolAccess.FULL,
            requiresConfirmation = true,
            budget = RunBudget(maxRounds = 20, maxToolCalls = 50, maxWallClockMs = 900_000),
        )
        assertEquals(AppMode.ACT, custom.engineMode)
        assertTrue(custom.agentic)
        assertFalse(custom.autonomous)
    }

    @Test
    fun confirmationIsIgnoredWithoutFullAccess() {
        val readOnly = ModeProfile(
            id = "custom-2",
            name = "Careful reader",
            toolAccess = ToolAccess.READ_ONLY,
            requiresConfirmation = true,
            budget = RunBudget(maxRounds = 8, maxToolCalls = 25, maxWallClockMs = 600_000),
        )
        // Read-only tools are safe, so the class stays CHAT_PLUS even with
        // the confirmation flag set.
        assertEquals(AppMode.CHAT_PLUS, readOnly.engineMode)
        assertFalse(readOnly.agentic)
    }

    @Test
    fun stockIdsMatchLegacyEnumNames() {
        // Old rows store the enum name; stock ids keep those strings.
        assertEquals("CHAT", ModeProfiles.CHAT.id)
        assertEquals("CHAT_PLUS", ModeProfiles.CHAT_PLUS.id)
        assertEquals("ACT", ModeProfiles.ACT.id)
        assertEquals("MAX", ModeProfiles.MAX.id)
    }

    @Test
    fun unknownIdsDegradeToPlainConversation() {
        val resolved = ModeProfiles.stocks.resolve("FUTURE_MODE")
        assertEquals("FUTURE_MODE", resolved.id)
        assertNull(resolved.name)
        assertEquals(AppMode.CHAT, resolved.engineMode)
        assertFalse(resolved.toolsVisible)
    }

    @Test
    fun knownIdsResolveToTheirProfile() {
        val resolved = ModeProfiles.stocks.resolve(ModeProfiles.MAX.id)
        assertEquals(ModeProfiles.MAX, resolved)
    }
}
