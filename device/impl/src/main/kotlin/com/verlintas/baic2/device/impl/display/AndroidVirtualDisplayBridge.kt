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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import com.verlintas.baic2.device.api.IVirtualDisplayService
import com.verlintas.baic2.device.api.ShellResult
import com.verlintas.baic2.device.api.ShellState
import com.verlintas.baic2.device.api.VirtualDisplayBridge
import com.verlintas.baic2.device.api.VirtualDisplayHandoff
import com.verlintas.baic2.device.api.VirtualDisplaySession
import com.verlintas.baic2.device.api.WirelessAdbBridge
import com.verlintas.baic2.device.api.WirelessAdbState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the resident display helper through Shizuku first, then root.
 *
 * The helper is a standalone `app_process` main class. It creates the
 * VirtualDisplay with an ImageReader surface owned by this process and hands
 * its Binder back through [VirtualDisplayHandoff]. Frames therefore never
 * touch the filesystem, and commands use the same audited shell path as the
 * rest of the tools.
 */
@Singleton
class AndroidVirtualDisplayBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellBridge: com.verlintas.baic2.device.api.ShellBridge,
    private val wirelessAdb: WirelessAdbBridge,
) : VirtualDisplayBridge {

    private class Session(
        val public: VirtualDisplaySession,
        val service: IVirtualDisplayService,
        val reader: ImageReader,
        val handlerThread: HandlerThread,
    )

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameLock = Any()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var latestImage: Image? = null

    private val lastActivity = AtomicLong(System.currentTimeMillis())

    @Volatile
    private var idleJob: Job? = null

    @Volatile
    private var keepAlive = false

    override fun available(): Boolean =
        shellBridge.state.value == ShellState.Ready ||
            wirelessAdb.state.value == WirelessAdbState.Connected ||
            wirelessAdb.state.value == WirelessAdbState.Disconnected ||
            wirelessAdb.state.value == WirelessAdbState.Connecting ||
            ROOT_BINARIES.any { File(it).exists() }

    override suspend fun ensure(): Result<VirtualDisplaySession> = mutex.withLock {
        withContext(Dispatchers.IO) {
            // An existing helper is a local Binder session. It must stay
            // usable even after Wi-Fi/ADB drops: only starting a new helper
            // needs the shell transport.
            session?.let { current ->
                val alive = current.service.asBinder().isBinderAlive &&
                    runCatching { current.service.ping() }.getOrDefault(false)
                if (alive) {
                    touch()
                    return@withContext Result.success(current.public)
                }
                closeSession(current)
            }
            if (!available()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "No privileged shell. app_research will use the foreground accessibility path.",
                    ),
                )
            }
            startSession()
        }
    }

    override suspend fun capture(): Result<ByteArray> {
        touch()
        val current = session ?: return Result.failure(IllegalStateException("No virtual display."))
        if (!current.service.asBinder().isBinderAlive) {
            return Result.failure(IllegalStateException("The display helper is gone."))
        }
        val deadline = System.currentTimeMillis() + 2_500L
        while (System.currentTimeMillis() < deadline) {
            val png = synchronized(frameLock) {
                latestImage?.let { imageToPng(it, current.public.width, current.public.height) }
            }
            if (png != null) return Result.success(png)
            delay(50)
        }
        return Result.failure(
            IllegalStateException("No frame from the virtual display yet."),
        )
    }

    override suspend fun launch(component: String): Result<Unit> {
        touch()
        val current = session ?: return Result.failure(IllegalStateException("No virtual display."))
        val binder = runCatching {
            current.service.startApp(current.public.displayId, component)
        }.getOrDefault(false)
        if (binder) return Result.success(Unit)
        // Older helpers (or an OEM rejection) fall back to the shell.
        return exec(
            "am start --display ${current.public.displayId} -f 0x10008000 -n ${quote(component)}",
        ).mapCatching { output ->
            if (output.contains("Error:") || output.contains("Exception")) {
                error(output.takeLast(300))
            }
        }
    }

    override suspend fun tap(x: Int, y: Int): Result<Unit> {
        touch()
        val current = session ?: return Result.failure(IllegalStateException("No virtual display."))
        val binderTap = runCatching { current.service.tap(current.public.displayId, x, y) }
            .getOrDefault(false)
        if (binderTap) {
            return Result.success(Unit)
        }
        return run("input -d ${current.public.displayId} tap $x $y")
    }

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Result<Unit> {
        touch()
        val current = session ?: return Result.failure(IllegalStateException("No virtual display."))
        if (runCatching {
                current.service.swipe(
                    current.public.displayId,
                    x1,
                    y1,
                    x2,
                    y2,
                    durationMs,
                )
            }.getOrDefault(false)
        ) {
            return Result.success(Unit)
        }
        return run(
            "input -d ${current.public.displayId} swipe $x1 $y1 $x2 $y2 $durationMs",
        )
    }

    override suspend fun keyEvent(keyCode: Int): Result<Unit> {
        touch()
        val current = session ?: return Result.failure(IllegalStateException("No virtual display."))
        val binderKey = runCatching { current.service.keyEvent(current.public.displayId, keyCode) }
            .getOrDefault(false)
        if (binderKey) {
            return Result.success(Unit)
        }
        return run("input -d ${current.public.displayId} keyevent $keyCode")
    }

    override suspend fun typeText(text: String): Result<Unit> {
        touch()
        val current = session ?: return Result.failure(IllegalStateException("No virtual display."))
        if (text.isEmpty()) return Result.success(Unit)
        val binderType = runCatching { current.service.typeText(current.public.displayId, text) }
            .getOrDefault(false)
        if (binderType) {
            return Result.success(Unit)
        }
        if (text.all { it.code in 0x20..0x7e }) {
            return run(
                "input -d ${current.public.displayId} text ${quote(text.replace(" ", "%s"))}",
            )
        }

        // Android's input command cannot carry CJK reliably; use the
        // clipboard and a PASTE key event, then restore the old clip when the
        // foreground app is allowed to read it.
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return Result.failure(IllegalStateException("Clipboard service unavailable."))
        val old = runCatching { manager.primaryClip }.getOrNull()
        val set = runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("BAIC2 research", text))
        }
        if (set.isFailure) {
            return Result.failure(
                IllegalStateException(
                    "Could not put the query on the clipboard: " +
                        set.exceptionOrNull()?.message,
                ),
            )
        }
        val pasted = run("input -d ${current.public.displayId} keyevent 279")
        delay(600)
        old?.let { runCatching { manager.setPrimaryClip(it) } }
        return pasted
    }

    override fun setKeepAlive(enabled: Boolean) {
        keepAlive = enabled
        if (enabled) {
            idleJob?.cancel()
            idleJob = null
        } else {
            touch()
        }
    }

    override fun release() {
        keepAlive = false
        idleJob?.cancel()
        idleJob = null
        scope.launch {
            mutex.withLock {
                session?.let { closeSession(it) }
            }
        }
    }

    private fun touch() {
        lastActivity.set(System.currentTimeMillis())
        scheduleIdleRelease()
    }

    private fun scheduleIdleRelease() {
        if (keepAlive) return
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (System.currentTimeMillis() - lastActivity.get() < IDLE_RELEASE_MS) return@launch
            mutex.withLock {
                session?.let { closeSession(it) }
            }
        }
    }

    private suspend fun startSession(): Result<VirtualDisplaySession> {
        val metrics = context.resources.displayMetrics
        val (width, height) = dimensions(metrics)
        val dpi = metrics.densityDpi
        val displayName = "baic2-agent"
        val nonce = UUID.randomUUID().toString()
        val pending = VirtualDisplayHandoff.expect(nonce)

        val logDir = File(context.getExternalFilesDir(null), "virtual-display")
            .takeIf { it.parentFile != null }
        logDir?.mkdirs()
        val log = logDir?.let { File(it, "helper.log") }
        log?.delete()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val thread = HandlerThread("baic2-display-frames").apply { start() }
        val handler = Handler(thread.looper)
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            synchronized(frameLock) {
                latestImage?.close()
                latestImage = image
            }
        }, handler)

        val command = buildString {
            append("nohup setsid app_process -Djava.class.path=")
            append(quote(context.packageCodePath))
            append(" /system/bin --nice-name=")
            append(context.packageName).append(":helper ")
            append(DisplayHelperMain::class.java.name)
            append(" --authority ").append(quote(context.packageName + ".helper"))
            append(" --package ").append(quote(context.packageName))
            append(" --uid ").append(context.applicationInfo.uid)
            append(" --user ").append(context.applicationInfo.uid / 100_000)
            append(" --nonce ").append(quote(nonce))
            append(" < /dev/null > ").append(quote(log?.absolutePath ?: "/dev/null"))
            append(" 2>&1 &")
        }

        // The app-token death recipient removes a helper when this process
        // dies; a stale helper from a crash is rejected by the nonce.
        val started = exec(command)
        if (started.isFailure) {
            reader.close()
            thread.quitSafely()
            VirtualDisplayHandoff.clear()
            return Result.failure(
                IllegalStateException(
                    "Could not start the display helper: " +
                        (started.exceptionOrNull()?.message ?: "no privileged shell."),
                ),
            )
        }

        val attachment = withTimeoutOrNull(12_000L) { pending.await() }
        if (attachment == null) {
            exec("killall -9 ${quote(context.packageName + ":helper")} 2>/dev/null || true")
            reader.close()
            thread.quitSafely()
            VirtualDisplayHandoff.clear()
            val detail = logTail(log)
            return Result.failure(
                IllegalStateException(
                    "The display helper did not connect" +
                        (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
                ),
            )
        }

        val service = IVirtualDisplayService.Stub.asInterface(attachment.binder)
        val displayId = runCatching {
            service.createDisplay(reader.surface, width, height, dpi, displayName)
        }.getOrElse { error ->
            exec("killall -9 ${quote(context.packageName + ":helper")} 2>/dev/null || true")
            reader.close()
            thread.quitSafely()
            VirtualDisplayHandoff.clear()
            return Result.failure(
                IllegalStateException(
                    "The display helper could not create a display: ${error.message}",
                ),
            )
        }
        if (displayId <= 0) {
            exec("killall -9 ${quote(context.packageName + ":helper")} 2>/dev/null || true")
            reader.close()
            thread.quitSafely()
            VirtualDisplayHandoff.clear()
            val detail = logTail(log)
            return Result.failure(
                IllegalStateException(
                    "The display helper could not create a display" +
                        (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
                ),
            )
        }

        val public = VirtualDisplaySession(
            displayId = displayId,
            width = width,
            height = height,
            helperPid = attachment.pid,
        )
        val created = Session(
            public = public,
            service = service,
            reader = reader,
            handlerThread = thread,
        )
        session = created
        touch()
        return Result.success(public)
    }

    private suspend fun closeSession(current: Session) {
        if (session === current) session = null
        runCatching { current.service.releaseDisplay(current.public.displayId) }
        runCatching { current.reader.close() }
        current.handlerThread.quitSafely()
        synchronized(frameLock) {
            latestImage?.close()
            latestImage = null
        }
        exec("kill ${current.public.helperPid} 2>/dev/null || true")
        VirtualDisplayHandoff.clear()
    }

    private fun logTail(log: File?): String? = runCatching {
        log?.takeIf { it.exists() }?.readText()?.trim()?.takeLast(300)
    }.getOrNull()

    private fun imageToPng(image: Image, width: Int, height: Int): ByteArray? = runCatching {
        val plane = image.planes.firstOrNull() ?: return@runCatching null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return@runCatching null
        val strideWidth = rowStride / pixelStride
        if (strideWidth < width) return@runCatching null

        val padded = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer.rewind())
        val cropped = if (strideWidth != width) {
            Bitmap.createBitmap(padded, 0, 0, width, height)
        } else {
            padded
        }
        val bytes = ByteArrayOutputStream().also {
            cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        if (cropped !== padded) cropped.recycle()
        padded.recycle()
        bytes
    }.getOrNull()

    private suspend fun run(command: String): Result<Unit> =
        exec(command).map { Unit }

    private suspend fun exec(command: String): Result<String> {
        // Prefer the built-in wireless transport: it needs no external app.
        if (wirelessAdb.state.value == WirelessAdbState.Connected ||
            wirelessAdb.state.value == WirelessAdbState.Disconnected ||
            wirelessAdb.state.value == WirelessAdbState.Connecting
        ) {
            val result = wirelessAdb.exec(command)
            if (result.isSuccess) return result
        }
        if (shellBridge.state.value == ShellState.Ready) {
            return when (val result = shellBridge.exec(command, timeoutMs = 20_000)) {
                is ShellResult.Output ->
                    if (result.exitCode == 0) {
                        Result.success(result.stdout)
                    } else {
                        Result.failure(IllegalStateException(result.stderr.ifBlank { result.stdout }))
                    }

                is ShellResult.Timeout -> Result.failure(IllegalStateException("Shell timed out."))
                is ShellResult.Unavailable -> Result.failure(IllegalStateException(result.reason))
            }
        }
        return withContext(Dispatchers.IO) {
            // AOSP su takes "WHO COMMAND...", Magisk su also accepts -c. Run
            // as shell so the display helper's package identity is correct.
            val attempts = listOf(
                listOf("su", "shell", "sh", "-c", command),
                listOf("su", "-c", command),
            )
            var lastError: String? = null
            for (argv in attempts) {
                try {
                    val process = ProcessBuilder(argv).redirectErrorStream(true).start()
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroy()
                        lastError = "root shell timed out"
                        continue
                    }
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    if (process.exitValue() == 0) return@withContext Result.success(output)
                    lastError = output.ifBlank { "root command failed" }
                } catch (e: Exception) {
                    lastError = e.message
                }
            }
            Result.failure(
                IllegalStateException(
                    "No Shizuku and no usable root shell" +
                        (lastError?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."),
                ),
            )
        }
    }

    private fun dimensions(metrics: android.util.DisplayMetrics): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            metrics.widthPixels to metrics.heightPixels
        }

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val IDLE_RELEASE_MS = 10 * 60_000L

        val ROOT_BINARIES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
        )
    }
}
