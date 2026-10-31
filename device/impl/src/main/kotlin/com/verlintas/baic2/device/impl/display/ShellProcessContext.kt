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

/** Constants for the identity that `app_process` presents to system services. */
internal object ShellIdentity {
    const val PACKAGE = "com.android.shell"
    const val UID = 2000
}

/**
 * A system context whose package belongs to the shell uid.
 *
 * `app_process` initially reports the system package, which package-facing
 * services reject for uid 2000. The wrapper changes only the identity-facing
 * methods; the underlying context still supplies the real system services.
 */
internal class ShellProcessContext internal constructor(delegate: Context) : ContextWrapper(delegate) {

    override fun getPackageName(): String = ShellIdentity.PACKAGE

    override fun getOpPackageName(): String = ShellIdentity.PACKAGE

    override fun getApplicationContext(): Context = this
}

@SuppressLint("PrivateApi")
internal fun createShellContext(): Context {
    val activityThread = Class.forName("android.app.ActivityThread")
    val mainThread = activityThread.getMethod("systemMain").invoke(null)
    val systemContext = activityThread.getMethod("getSystemContext")
        .invoke(mainThread) as Context
    return ShellProcessContext(systemContext)
}
