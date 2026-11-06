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

package com.verlintas.baic2.tools.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebFetcherPolicyTest {

    @Test
    fun publicWebUrlsAreAllowed() {
        assertTrue(isPublicHttpUrl("https://example.com/article"))
        assertTrue(isPublicHttpUrl("http://example.com/article"))
        assertTrue(isPublicHttpUrl("https://news.ycombinator.com/item?id=1"))
    }

    @Test
    fun privateAndUnsupportedHostsAreRejected() {
        assertFalse(isPublicHttpUrl("http://localhost:8080"))
        assertFalse(isPublicHttpUrl("http://127.0.0.1:8080"))
        assertFalse(isPublicHttpUrl("http://10.0.0.8"))
        assertFalse(isPublicHttpUrl("http://172.16.0.5"))
        assertFalse(isPublicHttpUrl("http://192.168.1.10"))
        assertFalse(isPublicHttpUrl("http://169.254.10.2"))
        assertFalse(isPublicHttpUrl("http://router.local"))
        assertFalse(isPublicHttpUrl("ftp://example.com"))
        assertFalse(isPublicHttpUrl("not a url"))
    }
}
