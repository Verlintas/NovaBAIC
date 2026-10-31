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

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.verlintas.baic2.device.api.IVirtualDisplayService
import com.verlintas.baic2.device.api.VirtualDisplayBridge
import com.verlintas.baic2.device.api.VirtualDisplayHandoff
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Debug-only smoke test for the resident virtual display. */
@AndroidEntryPoint
class DebugDisplayActivity : ComponentActivity() {

    @Inject
    lateinit var displays: VirtualDisplayBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("handoff_test", false)) {
            runHandoffTest()
            return
        }
        lifecycleScope.launch {
            val started = displays.ensure()
            Log.i(TAG, "ensure=$started")
            val session = started.getOrNull()
            if (session == null) {
                finish()
                return@launch
            }
            val again = displays.ensure().getOrNull()
            Log.i(TAG, "reuse=${again?.displayId} same=${again?.displayId == session.displayId}")
            val launched = displays.launch("com.android.settings/.Settings")
            Log.i(TAG, "launch=$launched")
            SystemClock.sleep(1_500)
            repeat(3) { attempt ->
                val frame = displays.capture()
                val bytes = frame.getOrNull()
                Log.i(TAG, "capture[$attempt]=${bytes?.size} ${frame.exceptionOrNull()?.message}")
                if (bytes != null) {
                    File(getExternalFilesDir(null), "debug-vd.png").writeBytes(bytes)
                    finish()
                    return@launch
                }
                delay(500)
            }
            finish()
        }
    }

    /**
     * Debug-only two-process test: print a nonce, wait for a manually started
     * app_process helper, then create and read a display through the biner.
     */
    private fun runHandoffTest() {
        lifecycleScope.launch {
            // Let the application's startup prewarm finish and clear its own
            // mailbox before arming this manual test.
            delay(13_000L)
            val nonce = UUID.randomUUID().toString()
            val pending = VirtualDisplayHandoff.expect(nonce)
            Log.i(TAG, "manual_nonce=$nonce")
            val attachment = withTimeoutOrNull(30_000L) { pending.await() }
            if (attachment == null) {
                Log.e(TAG, "manual_handoff=timeout")
                finish()
                return@launch
            }
            Log.i(TAG, "manual_handoff=pid ${attachment.pid}")
            val service = IVirtualDisplayService.Stub.asInterface(attachment.binder)
            val width = 1080
            val height = 2400
            val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val displayId = runCatching {
                service.createDisplay(reader.surface, width, height, 420, "baic2-test")
            }.getOrElse {
                Log.e(TAG, "manual_create", it)
                -1
            }
            Log.i(TAG, "manual_display=$displayId")
            if (displayId <= 0) {
                reader.close()
                finish()
                return@launch
            }
            Log.i(TAG, "manual_launch=${runCatching { service.startApp(displayId, "com.android.settings/.Settings") }}")
            delay(2_000)
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) {
                val plane = image.planes[0]
                val stride = plane.rowStride / plane.pixelStride
                val bitmap = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer.rewind())
                val output = File(getExternalFilesDir(null), "manual-vd.png")
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "manual_capture=${output.length()}")
                bitmap.recycle()
                image.close()
            } else {
                Log.e(TAG, "manual_capture=null")
            }
            runCatching { service.releaseDisplay(displayId) }
            reader.close()
            finish()
        }
    }

    private companion object {
        const val TAG = "BAIC2VD"
    }
}
