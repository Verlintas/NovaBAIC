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

import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.nio.ByteBuffer

/**
 * Debug-only proof of concept: an app-owned virtual display hosting a
 * Presentation + WebView. This does not use Shizuku or the shell helper.
 */
class DebugBrowserActivity : ComponentActivity() {

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var frameThread: HandlerThread? = null
    private lateinit var status: TextView
    private var clicked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Creating app-owned virtual display…" }
        setContentView(status)
        runCatching { createBrowserDisplay() }.onFailure { error ->
            Log.e(TAG, "create failed", error)
            status.text = "Failed: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun createBrowserDisplay() {
        val manager = getSystemService(DisplayManager::class.java)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val display = manager.createVirtualDisplay(
            "baic2-browser",
            width,
            height,
            density,
            reader.surface,
            flags,
            null,
            Handler(mainLooper),
        )
        virtualDisplay = display
        val target = display.display
        Log.i(TAG, "virtual display id=${target.displayId} valid=${target.isValid}")
        val pres = Presentation(this, target)
        val web = WebView(pres.context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "onPageFinished url=$url clicked=$clicked")
                    view?.evaluateJavascript(SNAPSHOT_JS) { result ->
                        Log.i(TAG, "snapshot=$result")
                        status.text = buildString {
                            append("Virtual display ").append(display.display.displayId)
                            append("\nURL: ").append(url)
                            append("\n").append(result?.take(700))
                        }
                        captureFrame()
                        if (!clicked && url?.contains("example.com") == true) {
                            clicked = true
                            view.evaluateJavascript(
                                "document.querySelector('a')?.click()",
                            ) { clickResult -> Log.i(TAG, "click=$clickResult") }
                        }
                    }
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
        Handler(mainLooper).postDelayed({
            runCatching { pres.show() }.onFailure { error ->
                Log.e(TAG, "presentation show failed", error)
            }
        }, 1_000L)
        webView = web
        status.text = "Virtual display ${display.display.displayId}\nLoading https://example.com…"
        web.loadUrl("https://example.com")
        startFrameReader(reader, width, height)
    }

    private fun startFrameReader(reader: ImageReader, width: Int, height: Int) {
        val thread = HandlerThread("browser-frames").apply { start() }
        frameThread = thread
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val strideWidth = rowStride / pixelStride
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val padded = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                val file = File(getExternalFilesDir(null), "browser-frame.png")
                file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cropped.recycle()
                padded.recycle()
                Log.i(TAG, "frame saved ${file.absolutePath} ${file.length()} bytes")
            } catch (error: Throwable) {
                Log.e(TAG, "frame failed", error)
            } finally {
                image.close()
            }
        }, Handler(thread.looper))
    }

    private fun captureFrame() {
        // The ImageReader listener already writes the latest frame; this is
        // just a log marker so the probe timeline is easy to follow.
        Log.i(TAG, "capture requested")
    }

    override fun onDestroy() {
        runCatching { presentation?.dismiss() }
        runCatching { webView?.destroy() }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { frameThread?.quitSafely() }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BAIC2BROWSER"

        const val SNAPSHOT_JS = """
            (function() {
              const text = (el) => (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || '').trim().slice(0, 100);
              return JSON.stringify({
                title: document.title,
                url: location.href,
                text: document.body ? document.body.innerText.slice(0, 1200) : '',
                links: Array.from(document.querySelectorAll('a')).slice(0, 20).map(a => ({text: text(a), href: a.href})),
                inputs: Array.from(document.querySelectorAll('input,textarea,button')).slice(0, 20).map(e => ({tag: e.tagName, type: e.type || '', text: text(e)}))
              });
            })()
        """
    }
}
