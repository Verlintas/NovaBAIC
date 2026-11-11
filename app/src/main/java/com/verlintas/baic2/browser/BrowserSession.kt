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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
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
import com.verlintas.baic2.device.api.WebFrame
import com.verlintas.baic2.device.api.WebResource
import com.verlintas.baic2.device.api.WebScreenshot
import com.verlintas.baic2.device.api.WebSnapshot
import com.verlintas.baic2.device.api.WebWaitResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            // Keep the buffer queue drained; DOM and view drawing are the
            // primary render interfaces for this session.
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
        // Wait for client-rendered pages to settle instead of a fixed sleep.
        settle(view)
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

    suspend fun screenshot(): Result<WebScreenshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        // Draw the WebView viewport directly. ImageReader frames from the
        // virtual display can contain only the window background while
        // Chromium composites the page on a separate layer, so the reader is
        // not dependable for the visual channel. Chromium may need one draw
        // to produce content, hence the short retry loop.
        repeat(4) { attempt ->
            val frame = captureViewBitmap(view)
            if (frame != null && !frame.blank) {
                Log.i(
                    TAG,
                    "screenshot ${frame.width}x${frame.height} bytes=${frame.bytes.size}",
                )
                return Result.success(
                    WebScreenshot(frame.bytes, "image/png", frame.width, frame.height),
                )
            }
            if (attempt < 3) delay(120)
        }
        return Result.failure(
            IllegalStateException("screenshot_blank: the page rendered no visible pixels"),
        )
    }

    suspend fun click(ref: String): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val result = parseJsObject(evaluate(view, "($CLICK_JS)(${JSONObject.quote(ref)})"))
        when (result?.optString("status")) {
            "navigate" -> {
                val url = result.optString("url")
                if (!NetworkEndpointPolicy.validateBaseUrl(url)) {
                    return Result.failure(IllegalStateException("url_not_allowed: $url"))
                }
                val load = CompletableDeferred<Result<Unit>>()
                pageLoad = load
                val started = runCatching {
                    withContext(Dispatchers.Main) { view.loadUrl(url) }
                }.isSuccess
                if (!started) {
                    return Result.failure(IllegalStateException("navigation_failed: $url"))
                }
                withTimeoutOrNull(PAGE_TIMEOUT_MS) { load.await() }
                settle(view, CLICK_SETTLE_MS)
                return snapshot()
            }

            "ok" -> Unit
            "blocked" -> return Result.failure(
                IllegalStateException("action_blocked: ${result.optString("reason")}"),
            )
            "stale" -> return Result.failure(IllegalStateException("element_stale: take a fresh snapshot"))
            "missing" -> return Result.failure(IllegalStateException("element_not_found: $ref"))
            "disabled" -> return Result.failure(IllegalStateException("element_disabled: $ref"))
            else -> return Result.failure(IllegalStateException("click_failed: $ref"))
        }
        settle(view, CLICK_SETTLE_MS)
        return snapshot()
    }

    suspend fun type(ref: String, text: String): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val result = parseJsObject(
            evaluate(view, "($TYPE_JS)(${JSONObject.quote(ref)}, ${JSONObject.quote(text)})"),
        )
        when (result?.optString("status")) {
            "ok" -> Unit
            "blocked" -> return Result.failure(
                IllegalStateException("action_blocked: ${result.optString("reason")}"),
            )
            "stale" -> return Result.failure(IllegalStateException("element_stale: take a fresh snapshot"))
            "missing" -> return Result.failure(IllegalStateException("element_not_found: $ref"))
            "disabled" -> return Result.failure(IllegalStateException("element_disabled: $ref"))
            else -> return Result.failure(IllegalStateException("type_failed: $ref"))
        }
        settle(view, TYPE_SETTLE_MS)
        return snapshot()
    }

    suspend fun press(key: String): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val normalized = key.trim().lowercase()
        val keyCode = when (normalized) {
            "enter" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "arrowdown", "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "arrowup", "up" -> KeyEvent.KEYCODE_DPAD_UP
            "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
            "pageup" -> KeyEvent.KEYCODE_PAGE_UP
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "backspace" -> KeyEvent.KEYCODE_DEL
            else -> return Result.failure(IllegalArgumentException("unsupported_key: $key"))
        }
        if (normalized == "enter") {
            val guard = parseJsObject(evaluate(view, ACTIVE_GUARD_JS))
            when (guard?.optString("status")) {
                "ok" -> Unit
                "blocked" -> return Result.failure(
                    IllegalStateException("action_blocked: ${guard.optString("reason")}"),
                )
                else -> return Result.failure(IllegalStateException("key_guard_failed"))
            }
        }
        withContext(Dispatchers.Main) {
            view.requestFocus()
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        settle(view, KEY_SETTLE_MS)
        return snapshot()
    }

    suspend fun selectOption(ref: String, option: String): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val result = parseJsObject(
            evaluate(
                view,
                "($SELECT_JS)(${JSONObject.quote(ref)}, ${JSONObject.quote(option)})",
            ),
        )
        when (result?.optString("status")) {
            "ok" -> Unit
            "missing" -> return Result.failure(IllegalStateException("element_not_found: $ref"))
            "not_select" -> return Result.failure(IllegalStateException("element_not_select: $ref"))
            "option_not_found" -> return Result.failure(
                IllegalStateException(
                    "option_not_found: $option (options: ${result.optString("options")})",
                ),
            )
            else -> return Result.failure(IllegalStateException("select_failed: $ref"))
        }
        settle(view, TYPE_SETTLE_MS)
        return snapshot()
    }

    suspend fun setChecked(ref: String, checked: Boolean?): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val result = parseJsObject(
            evaluate(
                view,
                "($CHECK_JS)(${JSONObject.quote(ref)}, ${checked?.toString() ?: "null"})",
            ),
        )
        when (result?.optString("status")) {
            "ok" -> Unit
            "missing" -> return Result.failure(IllegalStateException("element_not_found: $ref"))
            "not_checkable" -> return Result.failure(
                IllegalStateException("element_not_checkable: $ref"),
            )
            else -> return Result.failure(IllegalStateException("check_failed: $ref"))
        }
        settle(view, TYPE_SETTLE_MS)
        return snapshot()
    }

    suspend fun back(): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val canGoBack = withContext(Dispatchers.Main) { view.canGoBack() }
        Log.i(TAG, "back canGoBack=$canGoBack")
        if (!canGoBack) return Result.failure(IllegalStateException("no_back_history"))
        withContext(Dispatchers.Main) { view.goBack() }
        settle(view, NAV_SETTLE_MS)
        return snapshot()
    }

    suspend fun forward(): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val canGoForward = withContext(Dispatchers.Main) { view.canGoForward() }
        if (!canGoForward) return Result.failure(IllegalStateException("no_forward_history"))
        withContext(Dispatchers.Main) { view.goForward() }
        settle(view, NAV_SETTLE_MS)
        return snapshot()
    }

    suspend fun reload(): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        withContext(Dispatchers.Main) { view.reload() }
        settle(view, NAV_SETTLE_MS)
        return snapshot()
    }

    suspend fun readArticle(): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val json = parseJsObject(evaluate(view, ARTICLE_JS))
            ?: return Result.failure(IllegalStateException("article_failed"))
        val text = json.optString("text").take(8_000)
        if (text.isBlank()) return snapshot()
        return Result.success(
            WebSnapshot(
                url = json.optString("url"),
                title = json.optString("title"),
                text = text,
                elements = emptyList(),
            ),
        )
    }

    suspend fun resources(): Result<List<WebResource>> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val json = parseJsObject(evaluate(view, RESOURCE_JS))
            ?: return Result.failure(IllegalStateException("resources_failed"))
        val array = json.optJSONArray("entries") ?: return Result.success(emptyList())
        val out = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (url.isBlank()) continue
                add(
                    WebResource(
                        url = url,
                        type = item.optString("type"),
                        durationMs = item.optLong("duration"),
                        bytes = item.optLong("size"),
                    ),
                )
            }
        }
        return Result.success(out)
    }

    suspend fun scroll(direction: String, ref: String? = null): Result<WebSnapshot> {
        touch()
        val view = awaitReady()
            ?: return Result.failure(IllegalStateException("browser_not_ready"))
        val result = parseJsObject(
            evaluate(
                view,
                "($SCROLL_JS)(${JSONObject.quote(direction)}, ${JSONObject.quote(ref.orEmpty())})",
            ),
        )
        val status = result?.optString("status").orEmpty()
        if (status != "ok") {
            return Result.failure(
                IllegalStateException(
                    if (status == "missing") "element_not_found: ${ref.orEmpty()}"
                    else "scroll_failed",
                ),
            )
        }
        settle(view, SCROLL_SETTLE_MS)
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
        settle(view, 1_500L)
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

    private fun parseJsObject(raw: String?): JSONObject? {
        val decoded = raw
            ?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            ?: return null
        return runCatching { JSONObject(decoded) }.getOrNull()
    }

    /**
     * Wait until the document is complete and its text/node counts have been
     * stable for two polls. This covers both full navigations and SPA route
     * changes without a fixed sleep.
     */
    private suspend fun settle(view: WebView, maxMs: Long = SETTLE_MAX_MS) {
        val deadline = SystemClock.elapsedRealtime() + maxMs
        var lastText = -1
        var lastNodes = -1
        var stable = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = parseJsObject(runCatching { evaluate(view, SETTLE_JS) }.getOrNull())
            if (state != null) {
                val ready = state.optString("ready")
                val text = state.optInt("text", -1)
                val nodes = state.optInt("nodes", -1)
                if (ready == "complete" && text == lastText && nodes == lastNodes) {
                    stable++
                    if (stable >= 2) return
                } else {
                    stable = 0
                }
                lastText = text
                lastNodes = nodes
            }
            kotlinx.coroutines.delay(SETTLE_POLL_MS)
        }
    }

    private data class CapturedFrame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val blank: Boolean,
    )

    /** Cheap uniformity check for a not-yet-rendered WebView frame. */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val stepX = maxOf(1, bitmap.width / 64)
        val stepY = maxOf(1, bitmap.height / 64)
        val row = IntArray(bitmap.width)
        var first = 0
        var sampled = 0
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                val pixel = row[x]
                if (sampled == 0) first = pixel else if (pixel != first) return false
                sampled++
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private suspend fun captureViewBitmap(view: WebView): CapturedFrame? =
        withContext(Dispatchers.Main) {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return@withContext null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val scaled = scaleDown(bitmap, MAX_SCREENSHOT_DIMENSION)
            val blank = isBlank(scaled)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            CapturedFrame(out.toByteArray(), scaled.width, scaled.height, blank)
        }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
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
                        inputType = item.optString("inputType"),
                        disabled = item.optBoolean("disabled"),
                        checked = item.optBoolean("checked"),
                        value = item.optString("value").takeIf { it.isNotBlank() },
                        frameUrl = item.optString("frameUrl").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        val frames = buildList {
            val array = json.optJSONArray("frames") ?: return@buildList
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (url.isBlank()) continue
                add(WebFrame(url = url, sameOrigin = item.optBoolean("sameOrigin")))
            }
        }
        return WebSnapshot(
            url = json.optString("url"),
            title = json.optString("title"),
            text = json.optString("text").take(8_000),
            elements = elements,
            frames = frames,
            scrollY = json.optInt("scrollY"),
            scrollHeight = json.optInt("scrollHeight"),
            viewportHeight = json.optInt("viewportHeight"),
            hasPasswordField = json.optBoolean("hasPasswordField"),
            hasCaptchaFrame = json.optBoolean("hasCaptchaFrame"),
            hasConsentBanner = json.optBoolean("hasConsentBanner"),
        )
    }

    private companion object {
        const val TAG = "BAIC2WEB"
        const val READY_TIMEOUT_MS = 8_000L
        const val PAGE_TIMEOUT_MS = 35_000L
        const val HANDOFF_TIMEOUT_MS = 5 * 60_000L
        const val IDLE_TIMEOUT_MS = 5 * 60_000L
        const val MIN_WAIT_MS = 500L
        const val MAX_WAIT_MS = 15_000L
        const val WAIT_POLL_MS = 250L
        const val SETTLE_MAX_MS = 6_000L
        const val SETTLE_POLL_MS = 250L
        const val CLICK_SETTLE_MS = 8_000L
        const val TYPE_SETTLE_MS = 2_500L
        const val KEY_SETTLE_MS = 2_500L
        const val NAV_SETTLE_MS = 8_000L
        const val SCROLL_SETTLE_MS = 1_500L
        const val MAX_SCREENSHOT_DIMENSION = 1600

        const val SETTLE_JS = """
            (function() {
              return JSON.stringify({
                ready: document.readyState,
                text: document.body ? document.body.innerText.length : 0,
                nodes: document.querySelectorAll('*').length
              });
            })()
        """

        const val WAIT_JS = """
            function(text, ref) {
              if (ref) {
                var entry = window.__baic2RefMap && window.__baic2RefMap[ref];
                if (entry && entry.el && entry.el.isConnected) return true;
                return !!document.querySelector('[data-baic2-ref="' + ref + '"]');
              }
              if (!text) return true;
              var body = document.body ? document.body.innerText : '';
              return body.toLowerCase().indexOf(text.toLowerCase()) >= 0;
            }
        """

        const val SNAPSHOT_JS = """
            (function() {
              var MAX = 120;
              var map = {};
              window.__baic2RefMap = map;
              var elements = [];
              var frames = [];
              var hasPassword = false;
              var hasCaptcha = false;
              var hasConsent = false;
              function visible(el) {
                if (!el || !el.isConnected) return false;
                var r = el.getBoundingClientRect();
                if (r.width <= 0 || r.height <= 0) return false;
                var s = getComputedStyle(el);
                return s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
              }
              function textOf(el) {
                return String(el.innerText || el.value || el.getAttribute('aria-label') ||
                  el.getAttribute('placeholder') || el.getAttribute('title') ||
                  el.getAttribute('alt') || '').trim().replace(/\s+/g, ' ').slice(0, 160);
              }
              function labelOf(el) {
                var tag = el.tagName.toLowerCase();
                if (tag === 'input' || tag === 'textarea' || tag === 'select') {
                  return String(el.getAttribute('aria-label') || el.getAttribute('placeholder') ||
                    el.getAttribute('title') || el.name || el.id || el.value || '')
                    .trim().replace(/\s+/g, ' ').slice(0, 160);
                }
                return textOf(el);
              }
              function add(el, frameUrl) {
                if (elements.length >= MAX || !visible(el)) return;
                var tag = el.tagName.toLowerCase();
                var type = (el.getAttribute('type') || '').toLowerCase();
                var ref = 'w' + (elements.length + 1);
                map[ref] = { el: el, frame: frameUrl || null };
                try { el.setAttribute('data-baic2-ref', ref); } catch (e) {}
                elements.push({
                  ref: ref,
                  label: labelOf(el),
                  role: el.getAttribute('role') || '',
                  tag: tag,
                  inputType: type,
                  href: el.href || '',
                  editable: !!el.isContentEditable || tag === 'input' || tag === 'textarea',
                  disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
                  checked: !!el.checked,
                  value: (tag === 'input' || tag === 'select' || tag === 'textarea')
                    ? String(el.value || '').slice(0, 120) : '',
                  frameUrl: frameUrl || ''
                });
              }
              function scan(root, frameUrl) {
                if (!root || !root.querySelectorAll) return;
                var nodes = root.querySelectorAll(
                  'a,button,input,textarea,select,summary,[role="button"],[role="link"],' +
                  '[role="tab"],[role="menuitem"],[role="checkbox"],[role="radio"],' +
                  '[role="combobox"],[contenteditable="true"]'
                );
                for (var i = 0; i < nodes.length; i++) add(nodes[i], frameUrl);
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) {
                  var el = all[j];
                  if (el.shadowRoot) scan(el.shadowRoot, frameUrl);
                  if (el.tagName === 'IFRAME') {
                    var src = el.src || '';
                    var same = false;
                    try {
                      var doc = el.contentDocument;
                      if (doc) { same = true; scan(doc, src); }
                    } catch (e) { same = false; }
                    if (frames.length < 10) {
                      frames.push({ url: src, sameOrigin: same });
                    }
                    if (!same && /recaptcha|hcaptcha|challenges\.cloudflare|turnstile/i.test(src)) {
                      hasCaptcha = true;
                    }
                  }
                }
                if (root.querySelectorAll) {
                  var passwords = root.querySelectorAll('input[type="password"]');
                  for (var p = 0; p < passwords.length; p++) {
                    if (visible(passwords[p])) {
                      hasPassword = true;
                      break;
                    }
                  }
                }
                if (root.querySelector && root.querySelector(
                  '[class*="g-recaptcha"],[id*="captcha"],[class*="h-captcha"],[class*="cf-turnstile"]'
                )) {
                  hasCaptcha = true;
                }
              }
              function collectText(root) {
                var out = '';
                try {
                  out += (root.body ? root.body.innerText : (root.innerText || root.textContent || ''));
                } catch (e) {}
                var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
                for (var i = 0; i < all.length; i++) {
                  if (all[i].shadowRoot) {
                    try { out += '\n' + (all[i].shadowRoot.textContent || ''); } catch (e) {}
                  }
                  if (all[i].tagName === 'IFRAME') {
                    try {
                      var doc = all[i].contentDocument;
                      if (doc && doc.body) {
                        out += '\n[frame: ' + (all[i].src || '') + ']\n' + doc.body.innerText;
                      }
                    } catch (e) {}
                  }
                }
                return out;
              }
              scan(document, '');
              var fixed = document.querySelectorAll(
                '[id*="cookie" i],[class*="cookie" i],[id*="consent" i],[class*="consent" i]'
              );
              for (var k = 0; k < fixed.length; k++) {
                var s = getComputedStyle(fixed[k]);
                if ((s.position === 'fixed' || s.position === 'sticky') && visible(fixed[k])) {
                  hasConsent = true;
                  break;
                }
              }
              var text = collectText(document);
              return JSON.stringify({
                url: location.href,
                title: document.title,
                text: text.slice(0, 8000),
                elements: elements,
                frames: frames,
                scrollY: window.scrollY || 0,
                scrollHeight: document.documentElement ? document.documentElement.scrollHeight : 0,
                viewportHeight: window.innerHeight || 0,
                hasPasswordField: hasPassword,
                hasCaptchaFrame: hasCaptcha,
                hasConsentBanner: hasConsent
              });
            })()
        """

        const val CLICK_JS = """
            function(ref) {
              var entry = window.__baic2RefMap && window.__baic2RefMap[ref];
              var el = entry && entry.el ? entry.el : document.querySelector('[data-baic2-ref="' + ref + '"]');
              if (!el) return JSON.stringify({status: 'missing'});
              if (!el.isConnected) return JSON.stringify({status: 'stale'});
              var tag = el.tagName.toLowerCase();
              var type = (el.getAttribute('type') || '').toLowerCase();
              if (el.disabled || el.getAttribute('aria-disabled') === 'true') {
                return JSON.stringify({status: 'disabled'});
              }
              if (tag === 'input' && type === 'file') {
                return JSON.stringify({status: 'blocked', reason: 'file upload is not supported'});
              }
              if (tag === 'a' && el.href) {
                if (el.download) {
                  return JSON.stringify({status: 'blocked', reason: 'downloads are not supported'});
                }
                var rawHref = el.getAttribute('href') || '';
                if (rawHref === '' || rawHref.charAt(0) === '#') {
                  el.scrollIntoView({block: 'center'});
                  el.click();
                  return JSON.stringify({status: 'ok'});
                }
                if (!/^https?:/i.test(el.href)) {
                  return JSON.stringify({status: 'blocked', reason: 'unsupported link scheme'});
                }
                // Load links from the host so the WebView records back/forward
                // history; JS el.click() can replace the current entry.
                return JSON.stringify({status: 'navigate', url: el.href});
              }
              el.scrollIntoView({block: 'center', inline: 'center'});
              el.focus();
              el.click();
              return JSON.stringify({status: 'ok'});
            }
        """

        const val TYPE_JS = """
            function(ref, text) {
              var entry = window.__baic2RefMap && window.__baic2RefMap[ref];
              var el = entry && entry.el ? entry.el : document.querySelector('[data-baic2-ref="' + ref + '"]');
              if (!el) return JSON.stringify({status: 'missing'});
              if (!el.isConnected) return JSON.stringify({status: 'stale'});
              var type = (el.getAttribute('type') || '').toLowerCase();
              if (type === 'password') {
                return JSON.stringify({status: 'blocked', reason: 'use show_to_user for password fields'});
              }
              if (type === 'file') {
                return JSON.stringify({status: 'blocked', reason: 'file upload is not supported'});
              }
              el.scrollIntoView({block: 'center'});
              el.focus();
              if (el.isContentEditable) {
                el.innerText = text;
                el.dispatchEvent(new InputEvent('input', {bubbles: true, data: text, inputType: 'insertText'}));
                return JSON.stringify({status: 'ok'});
              }
              var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value');
              if (setter && setter.set) setter.set.call(el, text); else el.value = text;
              el.dispatchEvent(new Event('input', {bubbles: true}));
              el.dispatchEvent(new Event('change', {bubbles: true}));
              return JSON.stringify({status: 'ok'});
            }
        """

        const val SELECT_JS = """
            function(ref, option) {
              var entry = window.__baic2RefMap && window.__baic2RefMap[ref];
              var el = entry && entry.el ? entry.el : document.querySelector('[data-baic2-ref="' + ref + '"]');
              if (!el) return JSON.stringify({status: 'missing'});
              if (el.tagName.toLowerCase() !== 'select') {
                return JSON.stringify({status: 'not_select'});
              }
              var opts = Array.prototype.slice.call(el.options);
              var wanted = String(option || '').trim().toLowerCase();
              var match = null;
              for (var i = 0; i < opts.length; i++) {
                var o = opts[i];
                if (String(o.value).toLowerCase() === wanted ||
                    String(o.text).trim().toLowerCase() === wanted ||
                    String(o.text).toLowerCase().indexOf(wanted) >= 0) {
                  match = o;
                  break;
                }
              }
              if (!match) {
                return JSON.stringify({
                  status: 'option_not_found',
                  options: opts.map(function(o) { return o.text.trim(); }).slice(0, 20).join(' | ')
                });
              }
              el.value = match.value;
              el.dispatchEvent(new Event('input', {bubbles: true}));
              el.dispatchEvent(new Event('change', {bubbles: true}));
              return JSON.stringify({status: 'ok'});
            }
        """

        const val CHECK_JS = """
            function(ref, checked) {
              var entry = window.__baic2RefMap && window.__baic2RefMap[ref];
              var el = entry && entry.el ? entry.el : document.querySelector('[data-baic2-ref="' + ref + '"]');
              if (!el) return JSON.stringify({status: 'missing'});
              var type = (el.getAttribute('type') || '').toLowerCase();
              if (type !== 'checkbox' && type !== 'radio') {
                return JSON.stringify({status: 'not_checkable'});
              }
              if (checked === null || el.checked !== checked) el.click();
              return JSON.stringify({status: 'ok'});
            }
        """

        const val ACTIVE_GUARD_JS = """
            (function() {
              var el = document.activeElement;
              if (!el) return JSON.stringify({status: 'ok'});
              var type = (el.getAttribute('type') || '').toLowerCase();
              if (type === 'password') {
                return JSON.stringify({status: 'blocked', reason: 'password_handoff'});
              }
              return JSON.stringify({status: 'ok'});
            })()
        """

        const val ARTICLE_JS = """
            (function() {
              function textOf(el) { return String(el.innerText || '').trim(); }
              var candidates = Array.prototype.slice.call(document.querySelectorAll(
                'article,main,[role="main"],.article,.post,.content,#content,.markdown-body'
              ));
              var best = document.body;
              var bestScore = 0;
              for (var i = 0; i < candidates.length; i++) {
                var text = textOf(candidates[i]);
                if (text.length < 200) continue;
                var links = candidates[i].querySelectorAll('a').length;
                var score = text.length - links * 80;
                if (score > bestScore) { bestScore = score; best = candidates[i]; }
              }
              return JSON.stringify({
                url: location.href,
                title: document.title,
                text: best ? textOf(best).slice(0, 12000) : ''
              });
            })()
        """

        const val RESOURCE_JS = """
            (function() {
              var entries = performance.getEntriesByType('resource') || [];
              var out = [];
              for (var i = entries.length - 1; i >= 0 && out.length < 30; i--) {
                var e = entries[i];
                if (e.initiatorType === 'fetch' || e.initiatorType === 'xmlhttprequest' ||
                    e.name.indexOf('.json') >= 0 || e.name.indexOf('/api/') >= 0) {
                  out.push({
                    url: e.name,
                    type: e.initiatorType || 'resource',
                    duration: Math.round(e.duration || 0),
                    size: e.transferSize || 0
                  });
                }
              }
              return JSON.stringify({entries: out});
            })()
        """

        const val SCROLL_JS = """
            function(direction, ref) {
              var up = direction === 'up';
              var entry = ref ? (window.__baic2RefMap && window.__baic2RefMap[ref]) : null;
              var el = entry && entry.el ? entry.el : null;
              if (ref && !el) return JSON.stringify({status: 'missing'});
              var target = el;
              while (target && target !== document.documentElement && target !== document.body &&
                     (!target.scrollHeight || target.scrollHeight <= target.clientHeight + 4)) {
                target = target.parentElement;
              }
              if (target && target !== document.documentElement && target !== document.body) {
                var delta = (target.clientHeight || window.innerHeight) * 0.8;
                target.scrollTop += up ? -delta : delta;
              } else {
                var pageDelta = window.innerHeight * 0.8;
                window.scrollBy(0, up ? -pageDelta : pageDelta);
              }
              return JSON.stringify({status: 'ok'});
            }
        """
    }
}
