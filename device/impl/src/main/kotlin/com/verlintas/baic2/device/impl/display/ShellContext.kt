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

package com.verlintas.baic2.device.impl.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper

/**
 * A context that presents the shell identity.
 *
 * `app_process` has a system context whose package is `android`; system
 * services check that the package belongs to the caller's uid, so every
 * package-facing call must report `com.android.shell` (uid 2000). The
 * ContentResolver is built on this wrapper, not on the base system context,
 * so provider calls also carry the shell attribution source.
 */
internal class ShellContext private constructor(base: Context) : ContextWrapper(base) {

    override fun getPackageName(): String = SHELL_PACKAGE

    override fun getOpPackageName(): String = SHELL_PACKAGE

    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"

        @SuppressLint("PrivateApi")
        fun create(): Context {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            return ShellContext(system)
        }
    }
}
