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

package com.verlintas.baic2.core.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.verlintas.baic2.core.model.Attachment
import com.verlintas.baic2.core.model.AttachmentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports picker URIs into app-private files (images, downscaled) or inline
 * text (bounded), and materializes base64 payloads for outbound requests.
 */
@Singleton
class AttachmentProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun importImage(uri: Uri): Result<Attachment> = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "unreadable_image" }

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_DIMENSION * 2 ||
                bounds.outHeight / sampleSize > MAX_DIMENSION * 2
            ) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: error("unreadable_image")

            val scaled = scaleDown(decoded, MAX_DIMENSION)
            val directory = File(context.filesDir, ATTACHMENT_DIR).apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()

            Attachment(
                id = UUID.randomUUID().toString(),
                kind = AttachmentKind.IMAGE,
                mimeType = "image/jpeg",
                fileName = displayName(uri),
                localPath = file.absolutePath,
                sizeBytes = file.length(),
            )
        }
    }

    suspend fun importTextFile(uri: Uri): Result<Attachment> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (total <= MAX_TEXT_BYTES) {
                    val read = stream.read(chunk)
                    if (read == -1) break
                    total += read
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            } ?: error("unreadable_file")
            require(bytes.size <= MAX_TEXT_BYTES) { "file_too_large" }
            val mime = context.contentResolver.getType(uri) ?: "text/plain"
            require(mime.startsWith("text/") || mime in TEXT_LIKE_MIMES) { "unsupported_type" }
            Attachment(
                id = UUID.randomUUID().toString(),
                kind = AttachmentKind.TEXT,
                mimeType = mime,
                fileName = displayName(uri) ?: "attachment.txt",
                text = String(bytes, Charsets.UTF_8),
                sizeBytes = bytes.size.toLong(),
            )
        }
    }

    /** Reads a picked document (bytes + mime + display name) for extraction. */
    suspend fun readDocument(uri: Uri): Result<UriDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (total <= MAX_DOCUMENT_BYTES) {
                    val read = stream.read(chunk)
                    if (read == -1) break
                    total += read
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            } ?: error("unreadable_file")
            require(bytes.size <= MAX_DOCUMENT_BYTES) { "file_too_large" }
            UriDocument(
                bytes = bytes,
                mimeType = context.contentResolver.getType(uri).orEmpty(),
                fileName = displayName(uri) ?: "attachment",
            )
        }
    }

    /** Wraps extracted document text as a text attachment. */
    fun importExtractedText(
        mimeType: String,
        fileName: String,
        text: String,
        sizeBytes: Long,
    ): Attachment = Attachment(
        id = UUID.randomUUID().toString(),
        kind = AttachmentKind.TEXT,
        mimeType = mimeType.ifBlank { "text/plain" },
        fileName = fileName,
        text = text.take(MAX_DOCUMENT_CHARS),
        sizeBytes = sizeBytes,
    )

    /** Persists raw image bytes (e.g. a screenshot) as a PNG attachment. */
    suspend fun importImageBytes(
        bytes: ByteArray,
        fileName: String = "screenshot.png",
    ): Result<Attachment> = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("unreadable_image")
            val scaled = scaleDown(decoded, MAX_DIMENSION)
            val directory = File(context.filesDir, ATTACHMENT_DIR).apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.png")
            file.outputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            Attachment(
                id = UUID.randomUUID().toString(),
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                fileName = fileName,
                localPath = file.absolutePath,
                sizeBytes = file.length(),
            )
        }
    }

    /** Fills [Attachment.base64] for images; other kinds pass through. */
    suspend fun withBase64(attachment: Attachment): Attachment = withContext(Dispatchers.IO) {
        if (attachment.kind != AttachmentKind.IMAGE || attachment.base64 != null) return@withContext attachment
        val path = attachment.localPath ?: return@withContext attachment
        val file = File(path)
        if (!file.exists()) return@withContext attachment
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        attachment.copy(base64 = encoded)
    }

    suspend fun delete(attachment: Attachment) = withContext(Dispatchers.IO) {
        attachment.localPath?.let { runCatching { File(it).delete() } }
    }

    /**
     * Remove old execution screenshots that are no longer referenced by a
     * message. User-picked images and documents are never touched.
     */
    suspend fun pruneScreenshotFiles(
        olderThanDays: Int,
        keepPaths: Set<String>,
    ): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        val directory = File(context.filesDir, ATTACHMENT_DIR)
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.filter { it.name.startsWith("run-") || it.name.startsWith("screenshot") }
            ?.filter { it.absolutePath !in keepPaths }
            ?.count { runCatching { it.delete() }.getOrDefault(false) }
            ?: 0
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

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    data class UriDocument(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String,
    )

    private companion object {
        const val ATTACHMENT_DIR = "attachments"
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 82
        const val MAX_TEXT_BYTES = 1024 * 1024
        const val MAX_DOCUMENT_BYTES = 12 * 1024 * 1024
        const val MAX_DOCUMENT_CHARS = 60_000
        val TEXT_LIKE_MIMES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-yaml",
        )
    }
}
