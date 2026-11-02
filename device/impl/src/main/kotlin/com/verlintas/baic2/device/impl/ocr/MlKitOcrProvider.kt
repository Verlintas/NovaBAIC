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

package com.verlintas.baic2.device.impl.ocr

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.verlintas.baic2.device.api.OcrLine
import com.verlintas.baic2.device.api.OcrProvider
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** ML Kit Chinese recognizer (also handles Latin text); works offline. */
@Singleton
class MlKitOcrProvider @Inject constructor() : OcrProvider {

    private val mutex = Mutex()
    private var recognizer = newRecognizer()

    @Volatile
    private var needsReset = false

    private fun newRecognizer() =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private fun resetRecognizer() {
        runCatching { recognizer.close() }
        recognizer = newRecognizer()
        needsReset = false
    }

    override suspend fun recognize(image: ByteArray): Result<List<OcrLine>> = mutex.withLock {
        if (needsReset) resetRecognizer()
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(image, 0, image.size)
        }.getOrNull()
            ?: return@withLock Result.failure(IllegalArgumentException("undecodable_image"))
        val recycled = AtomicBoolean(false)
        fun recycle() {
            if (recycled.compareAndSet(false, true)) bitmap.recycle()
        }
        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val task = recognizer.process(input)
            val result = withTimeoutOrNull(OCR_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    task.addOnCompleteListener { completed ->
                        // A failed ML Kit task can also throw while resolving
                        // result/exception. Keep that off the caller's stack:
                        // an uncaught callback exception kills the process.
                        val outcome = runCatching {
                            if (!completed.isSuccessful) {
                                throw completed.exception
                                    ?: IllegalStateException("ocr_failed_without_exception")
                            }
                            val text = completed.result
                            text.textBlocks.flatMap { block -> block.lines }.map { line ->
                                val bounds = line.boundingBox ?: Rect()
                                OcrLine(
                                    text = line.text,
                                    left = bounds.left,
                                    top = bounds.top,
                                    right = bounds.right,
                                    bottom = bounds.bottom,
                                )
                            }
                        }.fold(
                            onSuccess = { Result.success(it) },
                            onFailure = { error ->
                                needsReset = true
                                Result.failure(error)
                            },
                        )
                        recycle()
                        // The caller may have timed out or been cancelled. Now
                        // that the task is done it is safe to rebuild the
                        // recognizer on the next call.
                        if (continuation.isActive) {
                            continuation.resume(outcome)
                        } else {
                            needsReset = true
                        }
                    }
                    // Do not recycle here: ML Kit may still be using the
                    // bitmap. The completion listener recycles it once.
                }
            }
            if (result != null) return@withLock result
            // The task may still be running. Do not close the recognizer
            // mid-flight; the completion listener marks it for reset.
            Result.failure(TimeoutException("ocr_timeout"))
        } catch (cancelled: CancellationException) {
            needsReset = true
            throw cancelled
        } catch (error: Throwable) {
            needsReset = true
            recycle()
            Result.failure(error)
        }
    }

    private companion object {
        const val OCR_TIMEOUT_MS = 15_000L
    }
}
