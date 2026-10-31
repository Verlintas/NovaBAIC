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

package com.verlintas.baic2.device.impl.wireless

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LocalPortScanTest {

    @Test
    fun parsesKernelEphemeralRange() {
        assertEquals(32_768..60_999, LocalPortScan.parseRange("32768\t60999\n"))
        assertEquals(30_000..65_535, LocalPortScan.parseRange("0 99999"))
        assertEquals(30_000..65_535, LocalPortScan.parseRange(null))
        assertEquals(30_000..65_535, LocalPortScan.parseRange("only-one"))
    }

    @Test
    fun findsAListeningLoopbackPort() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val port = server.localPort
            val found = LocalPortScan.openPorts(
                host = "127.0.0.1",
                range = (port - 8).coerceAtLeast(1)..(port + 8).coerceAtMost(65_535),
                budgetMs = 2_000,
                concurrency = 32,
            )
            assertTrue(port in found, "expected $port in $found")
        }
    }
}
