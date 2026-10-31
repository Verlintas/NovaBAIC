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

package com.verlintas.baic2

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.verlintas.baic2.device.api.VirtualDisplayHandoff

/**
 * Receives the resident helper's Binder.
 *
 * The helper runs as shell/root and cannot bind to app services, so it calls
 * this exported provider after launching. The provider permission already
 * restricts callers to system-level identities; the explicit uid check is
 * defense in depth. The nonce makes an old helper's late handoff useless.
 */
class VirtualDisplayHelperProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val reply = Bundle()
        if (method != VirtualDisplayHandoff.METHOD_ATTACH) return reply

        val caller = Binder.getCallingUid()
        if (caller != SHELL_UID && caller != ROOT_UID) {
            reply.putBoolean(VirtualDisplayHandoff.KEY_ACCEPTED, false)
            return reply
        }

        val binder = extras?.getBinder(VirtualDisplayHandoff.KEY_BINDER)
        val nonce = extras?.getString(VirtualDisplayHandoff.KEY_NONCE).orEmpty()
        val pid = extras?.getInt(VirtualDisplayHandoff.KEY_PID) ?: -1
        val accepted = binder != null &&
            VirtualDisplayHandoff.accept(binder, pid, nonce)
        reply.putBoolean(VirtualDisplayHandoff.KEY_ACCEPTED, accepted)
        if (accepted) {
            // Keep a process-local binder alive so the helper can watch this
            // app and release the display when the app dies.
            val token = Binder()
            appToken = token
            reply.putBinder(VirtualDisplayHandoff.KEY_APP_TOKEN, token)
        }
        return reply
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0

        /** Strong reference keeps the app token alive while the helper holds it. */
        @Volatile
        var appToken: IBinder? = null
    }
}
