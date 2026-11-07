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

import com.verlintas.baic2.device.api.WebSnapshot

/** A small semantic state so the model knows why a page is not readable yet. */
enum class WebPageState {
    READY,
    LOGIN_REQUIRED,
    CAPTCHA,
    CONSENT_REQUIRED,
    BLOCKED,
    EMPTY,
}

internal object WebPageStateDetector {

    private val captcha = Regex(
        "captcha|recaptcha|hcaptcha|verify you are human|i'm not a robot|" +
            "人机验证|验证码|checking your browser|attention required",
        RegexOption.IGNORE_CASE,
    )

    private val blocked = Regex(
        "access denied|403 forbidden|unusual traffic|rate limit|too many requests|" +
            "请求过于频繁|访问被拒绝",
        RegexOption.IGNORE_CASE,
    )

    private val login = Regex(
        "sign in|signin|log in|login|登录|登入|вход|войти",
        RegexOption.IGNORE_CASE,
    )

    private val consent = Regex(
        "accept all|accept cookies|we use cookies|cookie consent|" +
            "同意.*cookie|接受.*cookie|принять.*cookie",
        RegexOption.IGNORE_CASE,
    )

    fun detect(snapshot: WebSnapshot): WebPageState {
        val haystack = (snapshot.url + "\n" + snapshot.title + "\n" + snapshot.text).take(8_000)
        if (snapshot.hasCaptchaFrame || captcha.containsMatchIn(haystack)) {
            return WebPageState.CAPTCHA
        }
        if (blocked.containsMatchIn(haystack)) {
            return WebPageState.BLOCKED
        }
        if (snapshot.hasPasswordField && login.containsMatchIn(haystack)) {
            return WebPageState.LOGIN_REQUIRED
        }
        if (snapshot.hasConsentBanner || consent.containsMatchIn(haystack)) {
            return WebPageState.CONSENT_REQUIRED
        }
        if (snapshot.text.trim().length < 20 && snapshot.elements.size <= 1) {
            return WebPageState.EMPTY
        }
        return WebPageState.READY
    }
}
