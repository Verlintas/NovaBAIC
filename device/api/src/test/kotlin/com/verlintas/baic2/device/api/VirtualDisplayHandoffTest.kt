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

package com.verlintas.baic2.device.api

import android.os.IBinder
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class VirtualDisplayHandoffTest {

    @Test
    fun acceptsTheArmedNonceOnlyOnce() = runBlocking {
        VirtualDisplayHandoff.clear()
        try {
            val pending = VirtualDisplayHandoff.expect("current")
            val binder = fakeBinder()

            assertFalse(VirtualDisplayHandoff.accept(binder, 41, "stale"))
            assertFalse(pending.isCompleted)
            assertTrue(VirtualDisplayHandoff.accept(binder, 42, "current"))

            val attachment = pending.await()
            assertEquals(42, attachment.pid)
            assertEquals("current", attachment.nonce)
            assertFalse(VirtualDisplayHandoff.accept(binder, 42, "current"))
        } finally {
            VirtualDisplayHandoff.clear()
        }
    }

    @Test
    fun aNewNonceRejectsAnOldHelper() = runBlocking {
        VirtualDisplayHandoff.clear()
        try {
            VirtualDisplayHandoff.expect("old")
            val pending = VirtualDisplayHandoff.expect("new")
            val binder = fakeBinder()

            assertFalse(VirtualDisplayHandoff.accept(binder, 7, "old"))
            assertTrue(VirtualDisplayHandoff.accept(binder, 8, "new"))
            assertEquals(8, pending.await().pid)
        } finally {
            VirtualDisplayHandoff.clear()
        }
    }

    private fun fakeBinder(): IBinder = Proxy.newProxyInstance(
        IBinder::class.java.classLoader,
        arrayOf(IBinder::class.java),
    ) { _, _, _ -> null } as IBinder
}
