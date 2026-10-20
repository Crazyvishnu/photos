package com.photosapp.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

data class CaptureTime(val seconds: Long, val source: String)

/** Called on IO. A download/file modification timestamp is not a capture timestamp. */
object MediaCaptureTime {
    private val exifFormat = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private val videoCompact = DateTimeFormatterBuilder().appendPattern("uuuuMMdd'T'HHmmss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd().appendOffsetId().toFormatter()

    fun fromSharedFile(context: Context, original: Uri, file: File, mime: String, receivedAt: Long): CaptureTime {
        val embedded = if (mime.startsWith("video/")) videoDate(file) else photoDate(file)
        val millis = embedded ?: runCatching {
            context.contentResolver.query(original, arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
            }
        }.getOrNull()
        return CaptureTime((millis ?: receivedAt) / 1000, if (millis != null) "metadata" else "received")
    }

    private fun photoDate(file: File): Long? = runCatching {
        val exif = ExifInterface(file)
        val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val value = original ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED) ?: return null
        val local = LocalDateTime.parse(value.trim(), exifFormat)
        val offset = exif.getAttribute(if (original != null) ExifInterface.TAG_OFFSET_TIME_ORIGINAL else ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
            ?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        if (offset != null) local.toInstant(offset).toEpochMilli() else local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun videoDate(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value, videoCompact).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        } catch (_: Exception) { null }
        finally { runCatching { retriever.release() } }
    }
}
