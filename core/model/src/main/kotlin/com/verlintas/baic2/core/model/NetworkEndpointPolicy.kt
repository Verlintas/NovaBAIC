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

import java.net.URI

/**
 * The manifest permits cleartext HTTP so a user can reach a self-hosted LAN
 * model server. That platform switch is global, so keep the app-level gate
 * narrow: HTTPS anywhere, HTTP only for loopback, RFC1918/link-local hosts and
 * private mDNS names.
 */
object NetworkEndpointPolicy {

    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home", ".internal")

    fun validateBaseUrl(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return false
        if (host.isEmpty()) return false
        return when (scheme) {
            "https" -> true
            "http" -> allowsCleartext(host)
            else -> false
        }
    }

    fun allowsCleartext(host: String): Boolean {
        val value = host.trim().lowercase().removeSurrounding("[", "]")
        if (value.isEmpty()) return false
        if (value == "localhost" || value == "127.0.0.1" || value == "::1") return true
        if (PRIVATE_SUFFIXES.any { value.endsWith(it) }) return true
        if (value.contains(':')) {
            // IPv6 ULA fc00::/7 and link-local fe80::/10.
            return value.startsWith("fc") || value.startsWith("fd") ||
                value.startsWith("fe8") || value.startsWith("fe9") ||
                value.startsWith("fea") || value.startsWith("feb")
        }
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }
}
