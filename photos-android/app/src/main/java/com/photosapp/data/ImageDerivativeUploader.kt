package com.photosapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object ImageDerivativeUploader {
    const val MARKER = "photosapp.derivative.v1"
    const val FOLDER_MARKER = "photosapp.derivative.folder.v1"
    const val VERSION = "1"
    const val GRID_KEY = "gridDerivativeId"
    const val SCREEN_KEY = "screenDerivativeId"
    private const val PREFS = "photos_derivatives_v1"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val WEBP = "image/webp".toMediaType()

    fun locallyComplete(context: Context, email: String, originalId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("$email|$originalId", false)

    private fun markComplete(context: Context, email: String, originalId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("$email|$originalId", true).apply()
    }

    suspend fun ensure(context: Context, email: String, originalId: String, uri: Uri, originalName: String) {
        if (locallyComplete(context, email, originalId)) return
        val api = ServiceLocator.getApiForAccount(email)
        val original = api.getFile(originalId)
        if (original.trashed || original.appProperties?.get("library") != CloudLibraryRepository.APP_MARKER)
            throw IOException("Original is not available for derivative generation")

        val current = original.appProperties.orEmpty().toMutableMap()
        var gridId = current[GRID_KEY]
        var screenId = current[SCREEN_KEY]
        if (!gridId.isNullOrBlank() && !screenId.isNullOrBlank() && current["derivativeVersion"] == VERSION) {
            markComplete(context, email, originalId)
            return
        }

        if (gridId.isNullOrBlank()) gridId = findExisting(email, originalId, "grid")
        if (screenId.isNullOrBlank()) screenId = findExisting(email, originalId, "screen")
        val folderId = if (gridId.isNullOrBlank() || screenId.isNullOrBlank()) ensureFolder(email) else null

        var generated: Pair<ByteArray, ByteArray>? = null
        if (gridId.isNullOrBlank() || screenId.isNullOrBlank()) {
            generated = withContext(Dispatchers.IO) { encodePair(context, uri) }
        }
        if (gridId.isNullOrBlank()) gridId = upload(email, requireNotNull(folderId), originalId, originalName, "grid", requireNotNull(generated).first)
        if (screenId.isNullOrBlank()) screenId = upload(email, requireNotNull(folderId), originalId, originalName, "screen", requireNotNull(generated).second)

        current[GRID_KEY] = requireNotNull(gridId)
        current[SCREEN_KEY] = requireNotNull(screenId)
        current["derivativeVersion"] = VERSION
        val props = JSONObject().apply { current.forEach { (k, v) -> put(k, v) } }
        val body = JSONObject().put("appProperties", props).toString().toRequestBody(JSON)
        api.updateFileMetadata(originalId, body)
        markComplete(context, email, originalId)
    }

    private suspend fun findExisting(email: String, originalId: String, kind: String): String? {
        val q = "appProperties has { key='library' and value='$MARKER' } and appProperties has { key='originalId' and value='$originalId' } and appProperties has { key='kind' and value='$kind' } and trashed=false"
        return ServiceLocator.getApiForAccount(email).searchFiles(q, pageSize = 10).files.firstOrNull()?.id
    }

    private suspend fun ensureFolder(email: String): String {
        val api = ServiceLocator.getApiForAccount(email)
        val q = "mimeType='application/vnd.google-apps.folder' and appProperties has { key='library' and value='$FOLDER_MARKER' } and trashed=false"
        api.searchFiles(q, pageSize = 10).files.firstOrNull()?.let { return it.id }
        val metadata = JSONObject()
            .put("name", "Photos App optimized previews")
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("appProperties", JSONObject().put("library", FOLDER_MARKER).put("version", VERSION))
        return api.createMetadataFile(metadata.toString().toRequestBody(JSON)).id
    }

    private suspend fun upload(email: String, folderId: String, originalId: String, originalName: String, kind: String, bytes: ByteArray): String {
        val api = ServiceLocator.getApiForAccount(email)
        val id = ServiceLocator.reserveDriveFileId(email)
        val metadata = JSONObject()
            .put("id", id)
            .put("name", "Preview $kind - $originalName.webp")
            .put("mimeType", "image/webp")
            .put("parents", JSONArray().put(folderId))
            .put("appProperties", JSONObject()
                .put("library", MARKER)
                .put("version", VERSION)
                .put("originalId", originalId)
                .put("kind", kind))
        val body = MultipartBody.Builder("photos_" + UUID.randomUUID())
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(JSON))
            .addPart(bytes.toRequestBody(WEBP))
            .build()
        val uploaded = api.uploadDerivative(body)
        if (uploaded.id != id) throw IOException("Drive returned an unexpected derivative id")
        return id
    }

    private fun encodePair(context: Context, uri: Uri): Pair<ByteArray, ByteArray> {
        val oriented = decodeOriented(context, uri)
        try {
            val screen = scale(oriented, 2048)
            try {
                val grid = scale(screen, 512)
                try {
                    return encode(grid, 76) to encode(screen, 84)
                } finally {
                    if (grid !== screen) grid.recycle()
                }
            } finally {
                if (screen !== oriented) screen.recycle()
            }
        } finally {
            oriented.recycle()
        }
    }

    private fun decodeOriented(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Image is unavailable")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Image dimensions are unavailable")
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 3072) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: throw IOException("Image could not be decoded")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
        }
        if (matrix.isIdentity) return bitmap
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    private fun scale(source: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(source.width, source.height)
        if (edge <= maxEdge) return source
        val ratio = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(source, (source.width * ratio).roundToInt().coerceAtLeast(1), (source.height * ratio).roundToInt().coerceAtLeast(1), true)
    }

    @Suppress("DEPRECATION")
    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        if (!bitmap.compress(format, quality, out)) throw IOException("Image derivative encoding failed")
        return out.toByteArray()
    }
}
