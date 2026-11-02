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

import android.content.Context
import android.content.Intent
import com.verlintas.baic2.core.model.NetworkEndpointPolicy
import com.verlintas.baic2.device.api.WebBrowser
import com.verlintas.baic2.device.api.WebSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class AndroidWebBrowser @Inject constructor(
    @ApplicationContext private val context: Context,
) : WebBrowser {

    private val mutex = Mutex()

    override suspend fun open(url: String): Result<WebSnapshot> = mutex.withLock {
        if (!NetworkEndpointPolicy.validateBaseUrl(url)) {
            return@withLock Result.failure(
                IllegalArgumentException("url_not_allowed: use HTTPS or a private LAN HTTP address"),
            )
        }
        val session = ensureSession()
            ?: return@withLock Result.failure(
                IllegalStateException("browser_host_failed: could not start the browser session"),
            )
        session.open(url)
    }

    override suspend fun snapshot(): Result<WebSnapshot> = mutex.withLock {
        session()?.snapshot()
            ?: Result.failure(IllegalStateException("no_browser_session: open a URL first"))
    }

    override suspend fun click(ref: String): Result<WebSnapshot> = mutex.withLock {
        session()?.click(ref)
            ?: Result.failure(IllegalStateException("no_browser_session: open a URL first"))
    }

    override suspend fun type(ref: String, text: String): Result<WebSnapshot> = mutex.withLock {
        session()?.type(ref, text)
            ?: Result.failure(IllegalStateException("no_browser_session: open a URL first"))
    }

    override suspend fun scroll(direction: String): Result<WebSnapshot> = mutex.withLock {
        session()?.scroll(direction)
            ?: Result.failure(IllegalStateException("no_browser_session: open a URL first"))
    }

    override suspend fun showToUser(): Result<Unit> = mutex.withLock {
        val current = session()
            ?: return@withLock Result.failure(IllegalStateException("no_browser_session"))
        current.showToUser()
        Result.success(Unit)
    }

    override suspend fun hide(): Result<Unit> = mutex.withLock {
        // The MVP keeps the session alive for follow-up reads. Closing it is a
        // separate action once the host can be safely torn down.
        Result.success(Unit)
    }

    private fun session(): BrowserSession? = BrowserHostActivity.current

    private suspend fun ensureSession(): BrowserSession? {
        BrowserHostActivity.current?.let { return it }
        val started = runCatching {
            context.startActivity(
                Intent(context, BrowserHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!started) return null
        repeat(80) {
            BrowserHostActivity.current?.let { return it }
            delay(100)
        }
        return BrowserHostActivity.current
    }
}
