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

/** A shell-owned virtual display and the helper process behind it. */
data class VirtualDisplaySession(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val helperPid: Int,
)

/**
 * A resident, shell-owned display. The helper is launched with app_process
 * through Shizuku (or root when available); the app never needs a
 * MediaProjection consent for this display.
 */
interface VirtualDisplayBridge {
    /**
     * True when a privileged transport is already usable. Callers should not
     * start a helper when this is false: the foreground accessibility path is
     * the built-in fallback and needs no external app or root.
     */
    fun available(): Boolean

    /** Starts or reuses the resident helper. Failure is actionable. */
    suspend fun ensure(): Result<VirtualDisplaySession>

    /** Latest frame written by the helper, as PNG bytes. */
    suspend fun capture(): Result<ByteArray>

    suspend fun launch(component: String): Result<Unit>

    suspend fun tap(x: Int, y: Int): Result<Unit>

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<Unit>

    suspend fun keyEvent(keyCode: Int): Result<Unit>

    /** Types into the focused field; non-ASCII text goes through the clipboard. */
    suspend fun typeText(text: String): Result<Unit>

    /**
     * Opt-in: keep an existing session alive without the idle timer. Callers
     * must turn this off (or release the session) when keepalive stops.
     */
    fun setKeepAlive(enabled: Boolean) = Unit

    fun release()
}
