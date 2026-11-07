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

import com.verlintas.baic2.device.api.WebElement
import com.verlintas.baic2.device.api.WebSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class WebPageStateDetectorTest {

    private fun snapshot(
        url: String = "https://example.com/",
        title: String = "Example",
        text: String = "A public page with enough text to be considered ready.",
        elements: List<WebElement> = List(3) { WebElement("w$it", "link", "", "a") },
        hasPasswordField: Boolean = false,
        hasCaptchaFrame: Boolean = false,
        hasConsentBanner: Boolean = false,
    ) = WebSnapshot(
        url = url,
        title = title,
        text = text,
        elements = elements,
        hasPasswordField = hasPasswordField,
        hasCaptchaFrame = hasCaptchaFrame,
        hasConsentBanner = hasConsentBanner,
    )

    @Test
    fun detectsLoginForm() {
        val state = WebPageStateDetector.detect(
            snapshot(
                url = "https://example.com/login",
                title = "Sign in",
                text = "Sign in to continue. Password",
                hasPasswordField = true,
            ),
        )
        assertEquals(WebPageState.LOGIN_REQUIRED, state)
    }

    @Test
    fun publicPageWithSignInLinkIsNotALoginWall() {
        val state = WebPageStateDetector.detect(
            snapshot(
                title = "OpenAI Cookbook",
                text = "A public article. Sign in to save it for later.",
                hasPasswordField = false,
            ),
        )
        assertEquals(WebPageState.READY, state)
    }

    @Test
    fun detectsCaptchaByTextAndFrame() {
        assertEquals(
            WebPageState.CAPTCHA,
            WebPageStateDetector.detect(snapshot(text = "Verify you are human to continue")),
        )
        assertEquals(
            WebPageState.CAPTCHA,
            WebPageStateDetector.detect(snapshot(hasCaptchaFrame = true)),
        )
    }

    @Test
    fun detectsBlockedAndConsent() {
        assertEquals(
            WebPageState.BLOCKED,
            WebPageStateDetector.detect(snapshot(text = "Access denied: unusual traffic")),
        )
        assertEquals(
            WebPageState.CONSENT_REQUIRED,
            WebPageStateDetector.detect(snapshot(hasConsentBanner = true)),
        )
    }

    @Test
    fun detectsEmptyShell() {
        val state = WebPageStateDetector.detect(
            snapshot(text = "loading", elements = emptyList()),
        )
        assertEquals(WebPageState.EMPTY, state)
    }

    @Test
    fun shortResultPageIsNotAnEmptyShell() {
        val state = WebPageStateDetector.detect(
            snapshot(text = "Search results ?q=openai", elements = emptyList()),
        )
        assertEquals(WebPageState.READY, state)
    }
}
