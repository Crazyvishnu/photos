package com.photosapp.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LocalSourceException(cause: Throwable? = null) : IOException("The device file cannot be read", cause)
class SourceChangedException : IOException("The device file changed during backup")

/** Reads only the requested range. Local MediaStore videos do not need a second file-sized copy. */
class UploadSource(private val context: Context, val uri: Uri) {
    val size: Long = localRead {
        val descriptorSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            if (asset.declaredLength >= 0) asset.declaredLength else asset.parcelFileDescriptor.statSize
        } ?: throw IOException("File unavailable")
        val length = if (descriptorSize >= 0) descriptorSize else context.contentResolver.query(uri,
            arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1
        } ?: -1
        if (length <= 0) throw IOException("Empty file or unknown file size")
        length
    }

    suspend fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer=ByteArray(128*1024)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val n=input.read(buffer)
                    if(n<0) break
                    digest.update(buffer,0,n); count+=n
                }
            } ?: throw LocalSourceException()
        } catch(e: IOException) { throw LocalSourceException(e) }
        if(count!=size) throw SourceChangedException()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun read(offset: Long, length: Int): ByteArray = localRead {
        require(offset >= 0 && length > 0 && offset + length <= size)
        val bytes=ByteArray(length)
        val asset=context.contentResolver.openAssetFileDescriptor(uri,"r") ?: throw IOException("File unavailable")
        asset.use {
            asset.createInputStream().use { input ->
                // Normal device media is seekable. Pipe providers fall back to reopen-and-skip.
                val positioned=runCatching { input.channel.position(asset.startOffset + offset); true }.getOrDefault(false)
                if(!positioned) {
                    var remaining=offset
                    val scratch=ByteArray(64*1024)
                    while(remaining>0) {
                        currentCoroutineContext().ensureActive()
                        val n=input.read(scratch,0,minOf(scratch.size.toLong(),remaining).toInt())
                        if(n<0) throw IOException("File ended before saved offset")
                        remaining-=n
                    }
                }
                var read=0
                while(read<length) {
                    currentCoroutineContext().ensureActive()
                    val n=input.read(bytes,read,length-read)
                    if(n<0) throw IOException("File ended before expected size")
                    read+=n
                }
            }
        }
        bytes
    }

    private inline fun <T> localRead(block: () -> T): T = try { block() }
        catch(e: SecurityException) { throw e }
        catch(e: LocalSourceException) { throw e }
        catch(e: IOException) { throw LocalSourceException(e) }
}
