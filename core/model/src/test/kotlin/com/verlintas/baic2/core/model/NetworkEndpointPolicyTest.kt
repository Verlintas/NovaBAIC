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

class NetworkEndpointPolicyTest {

    @Test
    fun httpsIsAllowedEverywhere() {
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("https://api.example.com/v1"))
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("https://example.com"))
    }

    @Test
    fun httpIsLimitedToLoopbackAndPrivateHosts() {
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("http://localhost:11434/v1"))
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("http://127.0.0.1:8080"))
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("http://192.168.1.20:11434/v1"))
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("http://10.0.0.8:8000"))
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("http://172.16.0.5"))
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("http://172.31.255.1"))
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("http://169.254.10.2"))
        assertTrue(NetworkEndpointPolicy.validateBaseUrl("http://ollama.local:11434"))
        assertFalse(NetworkEndpointPolicy.validateBaseUrl("http://api.example.com/v1"))
        assertFalse(NetworkEndpointPolicy.validateBaseUrl("http://8.8.8.8"))
        assertFalse(NetworkEndpointPolicy.validateBaseUrl("http://172.32.0.1"))
    }

    @Test
    fun malformedOrUnsupportedSchemesAreRejected() {
        assertFalse(NetworkEndpointPolicy.validateBaseUrl("ftp://example.com"))
        assertFalse(NetworkEndpointPolicy.validateBaseUrl("not a url"))
        assertFalse(NetworkEndpointPolicy.validateBaseUrl(""))
    }
}
