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
    val inputType: String = "",
    val disabled: Boolean = false,
    val checked: Boolean = false,
    val value: String? = null,
    val frameUrl: String? = null,
)

/** An iframe visible on the page; cross-origin frames cannot be read as DOM. */
data class WebFrame(
    val url: String,
    val sameOrigin: Boolean,
)

/** A resource request observed through the Web Performance API. */
data class WebResource(
    val url: String,
    val type: String,
    val durationMs: Long = 0,
    val bytes: Long = 0,
)

/** A compact view of the current page plus stable element refs. */
data class WebSnapshot(
    val url: String,
    val title: String,
    val text: String,
    val elements: List<WebElement>,
    val frames: List<WebFrame> = emptyList(),
    val scrollY: Int = 0,
    val scrollHeight: Int = 0,
    val viewportHeight: Int = 0,
    val hasPasswordField: Boolean = false,
    val hasPaymentField: Boolean = false,
    val hasCaptchaFrame: Boolean = false,
    val hasConsentBanner: Boolean = false,
)

/** The snapshot after a wait, plus whether the requested condition appeared. */
data class WebWaitResult(
    val snapshot: WebSnapshot,
    val matched: Boolean,
)

/** A rendered viewport image of the current browser page. */
data class WebScreenshot(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    /** Set for segmented full-page captures, e.g. "part 2/5". */
    val label: String? = null,
    /** True when the page is taller than the captured segment cap. */
    val truncated: Boolean = false,
)

/**
 * A read-only browser session running in an app-owned virtual display. It is
 * intentionally independent of AccessibilityService, Shizuku and ADB: the
 * browser is our own WebView, not a third-party app.
 */
interface WebBrowser {
    suspend fun open(url: String): Result<WebSnapshot>

    suspend fun snapshot(): Result<WebSnapshot>

    /**
     * Capture the rendered page for a multimodal model or OCR. A normal call
     * returns one viewport; [fullPage] returns readable vertical segments.
     */
    suspend fun screenshot(fullPage: Boolean = false): Result<List<WebScreenshot>>

    suspend fun click(ref: String): Result<WebSnapshot>

    suspend fun type(ref: String, text: String): Result<WebSnapshot>

    /** Dispatch a real key event to the focused element (Enter, Tab, Escape…). */
    suspend fun press(key: String): Result<WebSnapshot>

    /** Choose an option in a native <select>. */
    suspend fun selectOption(ref: String, option: String): Result<WebSnapshot>

    /** Set a checkbox/radio; null toggles it. */
    suspend fun setChecked(ref: String, checked: Boolean?): Result<WebSnapshot>

    /** Scroll the window, or the scrollable container referenced by [ref]. */
    suspend fun scroll(direction: String, ref: String? = null): Result<WebSnapshot>

    /**
     * Poll until [text] appears in the page text or [ref] exists, then return
     * a snapshot. [timeoutMs] is clamped by the implementation.
     */
    suspend fun waitFor(text: String?, ref: String?, timeoutMs: Long): Result<WebWaitResult>

    suspend fun back(): Result<WebSnapshot>

    suspend fun forward(): Result<WebSnapshot>

    suspend fun reload(): Result<WebSnapshot>

    /** Article-focused text, ignoring nav/footer when possible. */
    suspend fun readArticle(): Result<WebSnapshot>

    /** Recent fetch/XHR/API resources observed by the page. */
    suspend fun resources(): Result<List<WebResource>>

    /** Bring the same session to the phone for login or a captcha. */
    suspend fun showToUser(): Result<Unit>

    /** Close the background session; the next [open] starts a fresh one. */
    suspend fun hide(): Result<Unit>

    /** Clear cookies, WebStorage, form data and the WebView cache. */
    suspend fun clearData(): Result<Unit>
}
