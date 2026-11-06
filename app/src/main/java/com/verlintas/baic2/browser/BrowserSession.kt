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

import android.app.Presentation
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.verlintas.baic2.core.model.NetworkEndpointPolicy
import com.verlintas.baic2.device.api.WebElement
import com.verlintas.baic2.device.api.WebSnapshot
import com.verlintas.baic2.device.api.WebWaitResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One read-only WebView hosted by an app-owned virtual display. The class is
 * intentionally self-contained: no AccessibilityService, Shizuku or ADB.
 */
class BrowserSession(private val host: ComponentActivity) {

    private val main = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var frameThread: HandlerThread? = null
    private var pageLoad: CompletableDeferred<Result<Unit>>? = null
    private var handoff: CompletableDeferred<Result<Unit>>? = null
    private var lastUrl: String? = null
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private var idleRunnable: Runnable? = null
    private var idlePaused = false

    fun start() {
        if (webView != null) return
        val manager = host.getSystemService(DisplayManager::class.java)
        val metrics = host.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val thread = HandlerThread("baic2-web-frames").apply { start() }
        frameThread = thread
        reader.setOnImageAvailableListener({ source ->
            // Keep the queue drained; the DOM is the primary interface and we
            // do not need every frame for this MVP.
            runCatching { source.acquireLatestImage()?.close() }
        }, Handler(thread.looper))
        val display = manager.createVirtualDisplay(
            "baic2-web",
            width,
            height,
            metrics.densityDpi,
            reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            null,
            main,
        )
        virtualDisplay = display
        val pres = Presentation(host, display.display)
        val web = WebView(pres.context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.saveFormData = false
            settings.savePassword = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "page finished $url")
                    pageLoad?.complete(Result.success(Unit))
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        pageLoad?.complete(
                            Result.failure(
                                IllegalStateException(error?.description?.toString() ?: "page_error"),
                            ),
                        )
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url?.toString() ?: return true
                    return !NetworkEndpointPolicy.validateBaseUrl(target)
                }
            }
        }
        val root = FrameLayout(pres.context).apply {
            addView(
                web,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        pres.setContentView(root)
        presentation = pres
        webView = web
        main.postDelayed({
            if (closed.get()) return@postDelayed
            runCatching { pres.show() }
                .onSuccess { ready.complete(Unit) }
                .onFailure { error ->
                    Log.e(TAG, "presentation failed", error)
                    ready.completeExceptionally(error)
                }
        }, 700L)
        touch()
    }

    suspend fun open(url: String): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        lastUrl = url
        val load = CompletableDeferred<Result<Unit>>()
        pageLoad = load
        withContext(Dispatchers.Main) { view.loadUrl(url) }
        val loaded = withTimeoutOrNull(PAGE_TIMEOUT_MS) { load.await() }
            ?: return Result.failure(IllegalStateException("page_load_timeout"))
        loaded.getOrElse { return Result.failure(it) }
        // Give client-rendered search pages a beat to paint their results.
        kotlinx.coroutines.delay(900)
        return snapshot()
    }

    suspend fun snapshot(): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val raw = evaluate(view, SNAPSHOT_JS)
            ?: return Result.failure(IllegalStateException("snapshot_failed"))
        return runCatching { parseSnapshot(raw) }
    }

    suspend fun click(ref: String): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val before = withContext(Dispatchers.Main) { view.url }
        val result = evaluate(view, "($CLICK_JS)(${JSONObject.quote(ref)})")
        if (result == null || !result.contains("\"ok\"")) {
            return Result.failure(IllegalStateException("element_not_found: $ref"))
        }
        val navigated = withTimeoutOrNull(NAV_TIMEOUT_MS) {
            while (withContext(Dispatchers.Main) { view.url } == before) {
                kotlinx.coroutines.delay(200)
            }
            true
        } ?: false
        // Give the new document a beat to render. A SPA may update the URL
        // before its DOM settles; a full navigation may still be painting.
        kotlinx.coroutines.delay(if (navigated) 1_000 else 500)
        return snapshot()
    }

    suspend fun type(ref: String, text: String): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val result = evaluate(
            view,
            "($TYPE_JS)(${JSONObject.quote(ref)}, ${JSONObject.quote(text)})",
        )
        if (result == null || !result.contains("\"ok\"")) {
            return Result.failure(IllegalStateException("element_not_found: $ref"))
        }
        kotlinx.coroutines.delay(350)
        return snapshot()
    }

    suspend fun scroll(direction: String): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        evaluate(view, "($SCROLL_JS)(${JSONObject.quote(direction)})")
        kotlinx.coroutines.delay(450)
        return snapshot()
    }

    suspend fun waitFor(
        text: String?,
        ref: String?,
        timeoutMs: Long,
    ): Result<WebWaitResult> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val queryText = text?.trim().orEmpty()
        val queryRef = ref?.trim().orEmpty()
        if (queryText.isEmpty() && queryRef.isEmpty()) {
            return Result.failure(IllegalArgumentException("wait_requires_text_or_ref"))
        }
        val limit = timeoutMs.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
        val deadline = SystemClock.elapsedRealtime() + limit
        var matched = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = evaluate(
                view,
                "($WAIT_JS)(${JSONObject.quote(queryText)}, ${JSONObject.quote(queryRef)})",
            )
            if (raw == "true") {
                matched = true
                break
            }
            kotlinx.coroutines.delay(WAIT_POLL_MS)
        }
        // Let the matching DOM settle before the snapshot.
        kotlinx.coroutines.delay(350)
        val snapshot = snapshot().getOrElse { return Result.failure(it) }
        return Result.success(WebWaitResult(snapshot = snapshot, matched = matched))
    }

    suspend fun showToUser(): Result<Unit> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val url = withContext(Dispatchers.Main) { view.url } ?: lastUrl
            ?: return Result.failure(IllegalStateException("no_page_to_show"))
        val deferred = CompletableDeferred<Result<Unit>>()
        handoff = deferred
        pauseIdle()
        try {
            val started = runCatching {
                host.startActivity(
                    Intent(host, BrowserLoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(BrowserLoginActivity.EXTRA_URL, url),
                )
            }.isSuccess
            if (!started) {
                handoff = null
                return Result.failure(IllegalStateException("handoff_activity_failed"))
            }
            return withTimeoutOrNull(HANDOFF_TIMEOUT_MS) { deferred.await() }
                ?: Result.failure(IllegalStateException("handoff_timeout"))
        } finally {
            resumeIdle()
        }
    }

    fun completeHandoff(cancelled: Boolean, currentUrl: String? = null) {
        val deferred = handoff ?: return
        handoff = null
        touch()
        val reloadUrl = currentUrl?.takeIf { it.startsWith("http") }
            ?: lastUrl
            ?: webView?.url
            ?: "about:blank"
        val host = runCatching { java.net.URI(reloadUrl).host }.getOrNull()
        val cookieNames = host?.let { hostName ->
            CookieManager.getInstance().getCookie("https://$hostName")
                ?.split(';')
                ?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
                ?.take(20)
        }.orEmpty()
        Log.i(TAG, "handoff finish reload=$reloadUrl cookies=$cookieNames")
        main.post {
            CookieManager.getInstance().flush()
            webView?.loadUrl(reloadUrl)
        }
        if (!deferred.isCompleted) {
            deferred.complete(
                if (cancelled) {
                    Result.failure(IllegalStateException("handoff_cancelled"))
                } else {
                    Result.success(Unit)
                },
            )
        }
    }

    /** Reset the idle-release timer after any browser operation. */
    fun touch() {
        main.post {
            if (closed.get() || idlePaused) return@post
            scheduleIdle()
        }
    }

    private fun scheduleIdle() {
        idleRunnable?.let { main.removeCallbacks(it) }
        val runnable = Runnable {
            if (closed.get() || idlePaused) return@Runnable
            Log.i(TAG, "idle release")
            runCatching { host.finish() }
        }
        idleRunnable = runnable
        main.postDelayed(runnable, IDLE_TIMEOUT_MS)
    }

    private fun pauseIdle() {
        main.post {
            idlePaused = true
            idleRunnable?.let { main.removeCallbacks(it) }
            idleRunnable = null
        }
    }

    private fun resumeIdle() {
        main.post {
            idlePaused = false
            if (!closed.get()) scheduleIdle()
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.Main) {
        webView?.clearCache(true)
        webView?.clearHistory()
        webView?.clearFormData()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        idlePaused = true
        idleRunnable?.let { main.removeCallbacks(it) }
        idleRunnable = null
        // Keep login cookies across a session release.
        runCatching { CookieManager.getInstance().flush() }
        runCatching { presentation?.dismiss() }
        runCatching { webView?.destroy() }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { frameThread?.quitSafely() }
        webView = null
        presentation = null
    }

    private suspend fun awaitReady(): WebView? {
        if (webView != null) return webView
        return withTimeoutOrNull(READY_TIMEOUT_MS) {
            runCatching { ready.await() }
            webView
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }

    private fun parseSnapshot(raw: String): WebSnapshot {
        val decoded = JSONTokener(raw).nextValue() as? String
            ?: error("snapshot_not_string")
        val json = JSONObject(decoded)
        val elements = buildList {
            val array = json.optJSONArray("elements") ?: return@buildList
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    WebElement(
                        ref = item.optString("ref"),
                        label = item.optString("label"),
                        role = item.optString("role"),
                        tag = item.optString("tag"),
                        href = item.optString("href").takeIf { it.isNotBlank() },
                        editable = item.optBoolean("editable"),
                    ),
                )
            }
        }
        return WebSnapshot(
            url = json.optString("url"),
            title = json.optString("title"),
            text = json.optString("text").take(6_000),
            elements = elements,
        )
    }

    private companion object {
        const val TAG = "BAIC2WEB"
        const val READY_TIMEOUT_MS = 8_000L
        const val PAGE_TIMEOUT_MS = 35_000L
        const val NAV_TIMEOUT_MS = 8_000L
        const val HANDOFF_TIMEOUT_MS = 5 * 60_000L
        const val IDLE_TIMEOUT_MS = 5 * 60_000L
        const val MIN_WAIT_MS = 500L
        const val MAX_WAIT_MS = 15_000L
        const val WAIT_POLL_MS = 250L

        const val WAIT_JS = """
            function(text, ref) {
              if (ref) return !!document.querySelector('[data-baic2-ref="' + ref + '"]');
              if (!text) return true;
              var body = document.body ? document.body.innerText : '';
              return body.toLowerCase().indexOf(text.toLowerCase()) >= 0;
            }
        """

        const val SNAPSHOT_JS = """
            (function() {
              document.querySelectorAll('[data-baic2-ref]').forEach(
                function(el) { el.removeAttribute('data-baic2-ref'); }
              );
              function visible(el) {
                var r = el.getBoundingClientRect();
                var s = getComputedStyle(el);
                return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
              }
              function label(el) {
                return (el.innerText || el.value || el.getAttribute('aria-label') ||
                  el.getAttribute('placeholder') || el.getAttribute('title') || '')
                  .trim().replace(/\s+/g, ' ').slice(0, 140);
              }
              var nodes = Array.prototype.slice.call(document.querySelectorAll(
                'a,button,input,textarea,select,[role="button"],[role="link"],[contenteditable="true"]'
              )).filter(visible).slice(0, 80);
              var elements = nodes.map(function(el, i) {
                var ref = 'w' + (i + 1);
                el.setAttribute('data-baic2-ref', ref);
                return {
                  ref: ref,
                  label: label(el),
                  role: el.getAttribute('role') || '',
                  tag: el.tagName.toLowerCase(),
                  href: el.href || '',
                  editable: el.isContentEditable || el.tagName === 'INPUT' || el.tagName === 'TEXTAREA'
                };
              });
              return JSON.stringify({
                url: location.href,
                title: document.title,
                text: document.body ? document.body.innerText.slice(0, 6000) : '',
                elements: elements
              });
            })()
        """

        const val CLICK_JS = """
            function(ref) {
              var el = document.querySelector('[data-baic2-ref="' + ref + '"]');
              if (!el) return 'missing';
              el.scrollIntoView({block: 'center'});
              el.click();
              return 'ok';
            }
        """

        const val TYPE_JS = """
            function(ref, text) {
              var el = document.querySelector('[data-baic2-ref="' + ref + '"]');
              if (!el) return 'missing';
              el.focus();
              if (el.isContentEditable) {
                el.innerText = text;
                el.dispatchEvent(new InputEvent('input', {bubbles: true, data: text, inputType: 'insertText'}));
                return 'ok';
              }
              var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value');
              if (setter && setter.set) setter.set.call(el, text); else el.value = text;
              el.dispatchEvent(new Event('input', {bubbles: true}));
              el.dispatchEvent(new Event('change', {bubbles: true}));
              return 'ok';
            }
        """

        const val SCROLL_JS = """
            function(direction) {
              var delta = window.innerHeight * 0.8;
              window.scrollBy(0, direction === 'up' ? -delta : delta);
              return 'ok';
            }
        """
    }
}
