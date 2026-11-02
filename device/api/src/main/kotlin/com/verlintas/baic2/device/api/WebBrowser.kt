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

/** One DOM element returned by [WebBrowser.snapshot]. */
data class WebElement(
    val ref: String,
    val label: String,
    val role: String,
    val tag: String,
    val href: String? = null,
    val editable: Boolean = false,
)

/** A compact view of the current page plus stable element refs. */
data class WebSnapshot(
    val url: String,
    val title: String,
    val text: String,
    val elements: List<WebElement>,
)

/**
 * A read-only browser session running in an app-owned virtual display. It is
 * intentionally independent of AccessibilityService, Shizuku and ADB: the
 * browser is our own WebView, not a third-party app.
 */
interface WebBrowser {
    suspend fun open(url: String): Result<WebSnapshot>

    suspend fun snapshot(): Result<WebSnapshot>

    suspend fun click(ref: String): Result<WebSnapshot>

    suspend fun type(ref: String, text: String): Result<WebSnapshot>

    suspend fun scroll(direction: String): Result<WebSnapshot>

    /** Bring the same session to the phone for login or a captcha. */
    suspend fun showToUser(): Result<Unit>

    suspend fun hide(): Result<Unit>
}
