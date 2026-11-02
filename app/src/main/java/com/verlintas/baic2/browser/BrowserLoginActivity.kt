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

import com.verlintas.baic2.R
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * A regular, visible WebView for the login / captcha handoff. It shares the
 * app's WebView cookie/profile with the background session, so the user can
 * sign in normally and the background page can reload afterwards.
 */
class BrowserLoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: "https://github.com/"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0B0D.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = getString(R.string.browser_login_title)
            textSize = 18f
            setTextColor(0xFFE8E8EA.toInt())
        }
        bar.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val done = Button(this).apply {
            text = getString(R.string.browser_login_done)
            textSize = 16f
            minWidth = (120 * resources.displayMetrics.density).toInt()
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setPadding(32, 16, 32, 16)
            setOnClickListener { complete(cancelled = false) }
        }
        bar.addView(done)
        val hint = TextView(this).apply {
            text = getString(R.string.browser_login_hint)
            textSize = 12f
            setTextColor(0xFF9A9AA3.toInt())
            setPadding(24, 0, 24, 12)
        }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.saveFormData = false
            settings.savePassword = false
            webViewClient = WebViewClient()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        root.addView(bar)
        root.addView(hint)
        root.addView(
            web,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bar.setPadding(24, bars.top + 16, 24, 16)
            insets
        }
        setContentView(root)
        webView = web
        web.loadUrl(url)
    }

    private fun complete(cancelled: Boolean) {
        if (completed) return
        completed = true
        CookieManager.getInstance().flush()
        BrowserHostActivity.current?.completeHandoff(cancelled, webView?.url)
        finish()
    }

    override fun onDestroy() {
        if (!completed) complete(cancelled = true)
        runCatching { webView?.destroy() }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "baic2.browser.login.url"
    }
}
