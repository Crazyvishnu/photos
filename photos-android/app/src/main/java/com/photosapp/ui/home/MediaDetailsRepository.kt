package com.photosapp.ui.home

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaDetails(
    val name: String, val dateMillis: Long, val sizeBytes: Long? = null,
    val mimeType: String? = null, val width: Int? = null, val height: Int? = null,
    val durationMillis: Long? = null, val folder: String? = null,
    val account: String? = null, val camera: String? = null, val unavailable: Boolean = false
)

/** Read real metadata on IO; missing permission/unsupported metadata never closes the viewer. */
class MediaDetailsRepository(private val context: Context) {
    suspend fun read(photo: LocalPhoto): MediaDetails = withContext(Dispatchers.IO) {
        val owner = photo.accountEmail ?: ServiceLocator.db.syncDao().getStatus(photo.uri.toString())?.accountEmail
        val base = MediaDetails(photo.name, photo.dateAdded * 1000L, account = owner)
        try {
            if (photo.accountEmail != null) {
                val file = ServiceLocator.getApiForAccount(photo.accountEmail).getFile(requireNotNull(photo.uri.lastPathSegment))
                val image = file.imageMediaMetadata
                val video = file.videoMediaMetadata
                base.copy(name = file.name, sizeBytes = file.size, mimeType = file.mimeType,
                    width = image?.width ?: video?.width, height = image?.height ?: video?.height,
                    durationMillis = video?.durationMillis,
                    camera = listOfNotNull(image?.cameraMake, image?.cameraModel).distinct().joinToString(" ").ifBlank { null })
            } else if (ServiceLocator.db.sharedMediaDao().get(photo.uri.toString()) != null) {
                val shared = requireNotNull(ServiceLocator.db.sharedMediaDao().get(photo.uri.toString()))
                val file = java.io.File(java.io.File(context.filesDir, "shared_media"), shared.storageName)
                if (photo.isVideo) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        base.copy(sizeBytes = file.length(), mimeType = shared.mimeType, folder = context.getString(com.photosapp.R.string.shared_folder),
                            width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                            height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                            durationMillis = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                    } finally { runCatching { retriever.release() } }
                } else {
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    base.copy(sizeBytes = file.length(), mimeType = shared.mimeType, folder = context.getString(com.photosapp.R.string.shared_folder),
                        width = options.outWidth.takeIf { it > 0 }, height = options.outHeight.takeIf { it > 0 }, camera = readCamera(photo))
                }
            } else {
                val projection = mutableListOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
                    MediaStore.Images.ImageColumns.DATE_TAKEN)
                if (Build.VERSION.SDK_INT >= 29) projection += MediaStore.MediaColumns.RELATIVE_PATH
                if (photo.isVideo) projection += MediaStore.Video.VideoColumns.DURATION
                context.contentResolver.query(photo.uri, projection.toTypedArray(), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use base.copy(unavailable = true)
                    fun long(column: String): Long? = c.getColumnIndex(column).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                    fun string(column: String): String? = c.getColumnIndex(column).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
                    base.copy(name = string(MediaStore.MediaColumns.DISPLAY_NAME) ?: photo.name,
                        sizeBytes = long(MediaStore.MediaColumns.SIZE),
                        mimeType = string(MediaStore.MediaColumns.MIME_TYPE),
                        width = long(MediaStore.MediaColumns.WIDTH)?.toInt()?.takeIf { it > 0 },
                        height = long(MediaStore.MediaColumns.HEIGHT)?.toInt()?.takeIf { it > 0 },
                        durationMillis = if (photo.isVideo) long(MediaStore.Video.VideoColumns.DURATION) else null,
                        folder = if (Build.VERSION.SDK_INT >= 29) string(MediaStore.MediaColumns.RELATIVE_PATH) else null,
                        camera = if (photo.isVideo) null else readCamera(photo))
                } ?: base.copy(unavailable = true)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            android.util.Log.w("MediaDetails", "Unable to read metadata", error)
            base.copy(unavailable = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun readCamera(photo: LocalPhoto): String? = try {
        context.contentResolver.openInputStream(photo.uri)?.use { stream ->
            val exif = androidx.exifinterface.media.ExifInterface(stream)
            listOfNotNull(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE), exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL))
                .distinct().joinToString(" ").ifBlank { null }
        }
    } catch (_: Exception) { null }
}
