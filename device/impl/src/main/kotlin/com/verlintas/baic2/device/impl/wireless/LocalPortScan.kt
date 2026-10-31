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

import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Last-resort discovery for the random TLS port that Android's adbd picks.
 * It only scans addresses owned by this phone, never the LAN.
 */
internal object LocalPortScan {

    val DEFAULT_RANGE = 30_000..65_535
    private const val DEFAULT_CONCURRENCY = 160
    private const val CONNECT_TIMEOUT_MS = 150L
    private const val SELECT_TIMEOUT_MS = 40L

    fun parseRange(text: String?): IntRange {
        val values = text?.trim()?.split(Regex("\\s+"))
            ?.mapNotNull(String::toIntOrNull)
            .orEmpty()
        if (values.size != 2) return DEFAULT_RANGE
        val low = values[0]
        val high = values[1]
        return if (low in 1_024..65_535 && high in low..65_535) {
            low..high
        } else {
            DEFAULT_RANGE
        }
    }

    fun readRange(): IntRange = runCatching {
        parseRange(File("/proc/sys/net/ipv4/ip_local_port_range").readText())
    }.getOrDefault(DEFAULT_RANGE)

    /**
     * Opens non-blocking TCP connections with a small in-flight window.
     * Returns only ports that accepted a connection before the budget expired.
     */
    suspend fun openPorts(
        host: String,
        range: IntRange,
        budgetMs: Long = 5_000L,
        concurrency: Int = DEFAULT_CONCURRENCY,
    ): List<Int> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + budgetMs.coerceAtLeast(500L)
        val context = currentCoroutineContext()
        val open = LinkedHashSet<Int>()
        val pending = HashMap<SelectionKey, Long>()
        val selector = Selector.open()
        var next = range.first
        try {
            while (
                (next <= range.last || pending.isNotEmpty()) &&
                System.currentTimeMillis() < deadline
            ) {
                context.ensureActive()
                while (next <= range.last && pending.size < concurrency) {
                    val port = next++
                    val channel = SocketChannel.open()
                    try {
                        channel.configureBlocking(false)
                        val key = channel.register(selector, SelectionKey.OP_CONNECT, port)
                        if (channel.connect(InetSocketAddress(host, port))) {
                            open += port
                            key.cancel()
                            channel.close()
                        } else {
                            pending[key] = System.currentTimeMillis()
                        }
                    } catch (_: Exception) {
                        runCatching { channel.close() }
                    }
                }

                if (selector.select(SELECT_TIMEOUT_MS) > 0) {
                    val iterator = selector.selectedKeys().iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        val channel = key.channel() as? SocketChannel
                        val port = key.attachment() as? Int
                        if (channel != null && port != null) {
                            val connected = runCatching { channel.finishConnect() }
                                .getOrDefault(false)
                            if (connected) open += port
                        }
                        pending.remove(key)
                        key.cancel()
                        runCatching { channel?.close() }
                    }
                }

                val now = System.currentTimeMillis()
                val stale = pending.filterValues { now - it >= CONNECT_TIMEOUT_MS }.keys.toList()
                for (key in stale) {
                    pending.remove(key)
                    key.cancel()
                    runCatching { (key.channel() as? SocketChannel)?.close() }
                }
            }
        } finally {
            for (key in pending.keys.toList()) {
                key.cancel()
                runCatching { (key.channel() as? SocketChannel)?.close() }
            }
            runCatching { selector.close() }
        }
        open.toList()
    }
}
