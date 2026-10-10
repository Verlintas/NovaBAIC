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

/**
 * Foreground-service progress for an active agent run: keeps the process
 * alive in the background and offers a stop action in the notification.
 */
interface RunNotifier {
    /** Handler invoked when the user taps stop in the notification. */
    fun setStopHandler(handler: (() -> Unit)?)

    /** [runId] lets the notification deep-link into the run detail. */
    fun startRunning(title: String, runId: Long? = null)

    fun stopRunning()

    /**
     * Posts a dismissible popup when a run ends while the app is not visible,
     * so the user knows the agent finished or was interrupted.
     */
    fun notifyFinished(title: String, success: Boolean, runId: Long?)

    /**
     * A plain chat reply finished while the app was not visible; tapping the
     * notification opens that conversation.
     */
    fun notifyConversationFinished(title: String, conversationId: Long)
}
