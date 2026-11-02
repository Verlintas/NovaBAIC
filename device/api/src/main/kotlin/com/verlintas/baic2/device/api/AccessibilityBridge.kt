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

import kotlinx.coroutines.flow.StateFlow

/** One accessibility node's text with its on-screen bounds. */
data class TextNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val editable: Boolean = false,
    val viewId: String? = null,
    val className: String? = null,
    val clickableClassName: String? = null,
    val hasToggle: Boolean = false,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * UI automation through an AccessibilityService. All gestures run on the
 * main thread; a missing service produces an actionable failure.
 */
interface AccessibilityBridge {
    val connected: StateFlow<Boolean>

    suspend fun tap(x: Int, y: Int): Result<Unit>

    /** Press and hold a point (e.g. context menus, drag handles). */
    suspend fun longPress(x: Int, y: Int): Result<Unit>

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit>

    suspend fun typeText(text: String): Result<Unit>

    /** key: back | home | recents | notifications | enter */
    suspend fun pressKey(key: String): Result<Unit>

    /** Interactive elements whose text/description contains [query]. */
    fun findText(query: String): List<TextNode>

    fun screenText(maxNodes: Int = 200): String

    /**
     * Text nodes from [displayId] instead of the active display.
     * Empty when the service cannot see that display.
     */
    fun findTextOnDisplay(displayId: Int, query: String): List<TextNode>

    fun screenTextOnDisplay(displayId: Int, maxNodes: Int = 300): String

    /** Text from every window (including system dialogs) on the default display. */
    fun screenTextAllWindows(maxNodes: Int = 400): String

    /** Text nodes from every window on the default display. */
    fun findTextAllWindows(query: String, maxNodes: Int = 400): List<TextNode>

    /**
     * Labelled interactive elements on the default display, in stable
     * top-to-bottom window order. Used to hand out short-lived element refs
     * (`e1`, `e2`, ...) so a model can tap without repeating coordinates.
     */
    fun snapshotNodes(maxNodes: Int = 60): List<TextNode>

    /** True when a text input on [displayId] owns input focus. */
    fun editableFocusedOnDisplay(displayId: Int): Boolean

    /**
     * The best search-like control. It scores resource ids, search view
     * classes, localized hints and editable fields; it is not a name match
     * against one hard-coded button label.
     */
    fun findSearchNode(
        hints: List<String> = emptyList(),
        preferredViewId: String? = null,
    ): TextNode?

    fun findSearchNodeOnDisplay(
        displayId: Int,
        hints: List<String> = emptyList(),
        preferredViewId: String? = null,
    ): TextNode?

    /** The first editable field on the active window, with its bounds. */
    fun editableNode(): TextNode?

    /** The first editable field on [displayId], with its bounds. */
    fun editableNodeOnDisplay(displayId: Int): TextNode?

    /** Click a text/description match on [displayId] via ACTION_CLICK. */
    fun clickTextOnDisplay(displayId: Int, query: String): Result<Unit>

    /** Click the first editable field on [displayId]. */
    fun clickEditableOnDisplay(displayId: Int): Result<Unit>

    /** Replace the focused/first editable field on [displayId]. */
    fun setTextOnDisplay(displayId: Int, text: String): Result<Unit>

    /** Submit the focused input on [displayId] (Android 11+). */
    fun pressEnterOnDisplay(displayId: Int): Result<Unit>

    /** Scroll the first scrollable node on [displayId]. */
    fun scrollOnDisplay(displayId: Int, forward: Boolean = true): Result<Unit>

    /**
     * Wait until the visible text on [displayId] changes or [timeoutMs] runs
     * out. Pass the text captured before an action as [previousText] so a
     * change that landed before this call is still detected. Returns false on
     * timeout; callers should keep a fallback instead of retrying forever.
     */
    suspend fun awaitContentChange(
        displayId: Int,
        timeoutMs: Long,
        previousText: String? = null,
    ): Boolean

    /** Package name of the app currently in the foreground, when known. */
    fun foregroundPackage(): String?

    /**
     * Package of the top application window on [displayId]. This is the
     * per-display counterpart used to verify that a virtual-display launch
     * really landed on that display instead of the phone.
     */
    fun foregroundPackageOnDisplay(displayId: Int): String?

    /** Active window title, when the service can read one. */
    fun windowTitle(): String?

    /** Display size in pixels. */
    fun screenSize(): Pair<Int, Int>?

    /** True when a text input currently owns the input focus (or one exists). */
    fun editableFocused(): Boolean
}
