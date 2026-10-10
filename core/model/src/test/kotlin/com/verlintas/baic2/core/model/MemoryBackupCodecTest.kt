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
import kotlin.test.assertTrue

class MemoryBackupCodecTest {

    private val backup = MemoryBackup(
        version = 1,
        exportedAt = 1_700_000_000_000L,
        core = MemoryBackupCore(user = "用户是 Verlin", context = "在做一个 Android agent"),
        notes = listOf(
            MemoryBackupNote(
                content = "用户喜欢冰拿铁",
                kind = "preference",
                importance = 4,
                source = "user",
                entities = listOf("Verlin"),
                whenAt = 1_699_000_000_000L,
                expiresAt = null,
                createdAt = 1_698_000_000_000L,
            ),
            MemoryBackupNote(content = "目前在乌兰浩特", kind = "fact", expiresAt = 1_701_000_000_000L),
        ),
        holds = listOf(MemoryBackupHold(content = "银行卡密码", reason = "隐私")),
    )

    @Test
    fun roundTripPreservesEverything() {
        val decoded = MemoryBackupCodec.decode(MemoryBackupCodec.encode(backup)).getOrThrow()
        assertEquals(backup, decoded)
    }

    @Test
    fun garbageIsRejectedWithoutThrowing() {
        assertTrue(MemoryBackupCodec.decode("not json").isFailure)
        assertTrue(MemoryBackupCodec.decode("""{"version":1,"notes":"nope"}""").isFailure)
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val result = MemoryBackupCodec.decode(
            """{"version":2,"exportedAt":1,"futureField":{"x":1},"notes":[]}""",
        )
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().notes.isNotEmpty())
    }
}
