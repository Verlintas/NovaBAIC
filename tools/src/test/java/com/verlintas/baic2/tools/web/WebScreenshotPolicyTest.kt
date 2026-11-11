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

import com.verlintas.baic2.device.api.WebFrame
import com.verlintas.baic2.device.api.WebSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebScreenshotPolicyTest {

    private fun snapshot(
        url: String = "https://example.com/article",
        text: String = "A normal article about software.",
        hasPasswordField: Boolean = false,
        hasPaymentField: Boolean = false,
        frames: List<WebFrame> = emptyList(),
    ) = WebSnapshot(
        url = url,
        title = "Example",
        text = text,
        elements = emptyList(),
        frames = frames,
        hasPasswordField = hasPasswordField,
        hasPaymentField = hasPaymentField,
    )

    @Test
    fun normalArticleIsAllowed() {
        assertNull(WebScreenshotPolicy.blockReason(snapshot()))
        assertNull(
            WebScreenshotPolicy.blockReason(
                snapshot(url = "https://news.example.com/payments/2026/story"),
            ),
        )
    }

    @Test
    fun passwordAndCardFormsAreBlocked() {
        assertEquals(
            "the page contains a password field",
            WebScreenshotPolicy.blockReason(snapshot(hasPasswordField = true)),
        )
        assertEquals(
            "the page contains a card/payment form",
            WebScreenshotPolicy.blockReason(snapshot(hasPaymentField = true)),
        )
    }

    @Test
    fun paymentPagesAndFramesAreBlocked() {
        assertEquals(
            "the page embeds a payment provider",
            WebScreenshotPolicy.blockReason(
                snapshot(
                    url = "https://shop.example.com/checkout",
                    frames = listOf(WebFrame("https://js.stripe.com/v3/elements-inner", false)),
                ),
            ),
        )
        assertEquals(
            "the page looks like a checkout/payment page",
            WebScreenshotPolicy.blockReason(
                snapshot(
                    url = "https://shop.example.com/checkout",
                    text = "Choose a payment method and enter your billing address.",
                ),
            ),
        )
    }

    @Test
    fun captchaChallengeIsBlocked() {
        assertEquals(
            "the page shows a CAPTCHA challenge",
            WebScreenshotPolicy.blockReason(
                snapshot(
                    url = "https://example.com/search",
                    text = "Please verify you are human before continuing.",
                ),
            ),
        )
    }
}
