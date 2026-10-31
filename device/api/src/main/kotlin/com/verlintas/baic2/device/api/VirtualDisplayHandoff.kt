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

import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred

/**
 * One-shot mailbox for the resident helper's Binder.
 *
 * The helper process cannot use startService on the app (background limits),
 * so it calls the app's exported provider with its binder. The provider and
 * the bridge live in the same process; this object carries the handoff from
 * the provider callback to the waiting `ensure()` call.
 *
 * Nothing in the app is exposed through this mailbox: only the shell/root
 * helper can call the provider, and the nonce rejects stale helpers.
 */
object VirtualDisplayHandoff {
    const val METHOD_ATTACH = "attach"
    const val KEY_BINDER = "binder"
    const val KEY_PID = "pid"
    const val KEY_NONCE = "nonce"
    const val KEY_ACCEPTED = "accepted"
    const val KEY_APP_TOKEN = "app_token"

    data class Attachment(
        val binder: IBinder,
        val pid: Int,
        val nonce: String,
    )

    private val lock = Any()
    private var expectedNonce: String? = null
    private var pending: CompletableDeferred<Attachment>? = null

    /** Arms the mailbox for [nonce]; any older helper is ignored. */
    fun expect(nonce: String): CompletableDeferred<Attachment> = synchronized(lock) {
        expectedNonce = nonce
        val deferred = CompletableDeferred<Attachment>()
        pending = deferred
        deferred
    }

    fun accept(binder: IBinder, pid: Int, nonce: String): Boolean = synchronized(lock) {
        if (nonce.isBlank() || nonce != expectedNonce) return false
        val deferred = pending ?: return false
        if (deferred.isCompleted) return false
        expectedNonce = null
        deferred.complete(Attachment(binder, pid, nonce))
        true
    }

    fun clear() {
        synchronized(lock) {
            expectedNonce = null
            pending = null
        }
    }
}
