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

package com.verlintas.baic2.browser

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Hosts the WebView session. The activity itself is a 1 px transparent window;
 * the browser is rendered on an app-owned virtual display. Keeping the activity
 * alive gives the Presentation a window token without covering the chat.
 */
class BrowserHostActivity : ComponentActivity() {

    private var session: BrowserSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(1, 1)
        window.setGravity(Gravity.TOP or Gravity.START)
        setContentView(
            TextView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                text = ""
            },
        )
        val created = BrowserSession(this)
        session = created
        current = created
        host = this
        created.start()
        Log.i(TAG, "browser host started")
    }

    override fun onDestroy() {
        if (host === this) host = null
        if (current === session) current = null
        session?.close()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BAIC2WEB"

        @Volatile
        var current: BrowserSession? = null
            private set

        @Volatile
        private var host: BrowserHostActivity? = null

        /** Finish the host activity; onDestroy closes the session. */
        fun closeCurrent() {
            val activity = host
            current = null
            activity?.runOnUiThread { activity.finish() }
        }
    }
}
