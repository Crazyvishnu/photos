package com.photosapp.data

import com.photosapp.data.local.db.UploadSession
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import okhttp3.ResponseBody
import java.io.IOException

class DriveUploadFailure(val status: Int, val reason: String) : IOException("Drive HTTP $status: $reason")
class UploadConnectionException(cause: Throwable) : IOException("Upload interrupted; saved session will be resumed",cause)
private class SessionExpired : IOException()

data class UploadedFile(val id: String, val recovered: Boolean)

/** Durable Drive session + pre-generated file ID prevent restarts and duplicate final commits. */
class DriveResumableUploader(private val progress: suspend (Long, Long)->Unit) {
    private val dao get()=ServiceLocator.db.uploadSessionDao()

    suspend fun upload(email: String, source: UploadSource, hash: String, metadata: JSONObject, chunkSize: Int, maxChunkSize: Int = 8 * 1024 * 1024,
                       checkSource: suspend ()->Unit): UploadedFile {
        val uri=source.uri.toString()
        val api=ServiceLocator.getApiForAccount(email)
        var saved=dao.get(uri)?.takeIf { it.accountEmail==email && it.sha256==hash && it.size==source.size }
        if(saved==null) {
            dao.delete(uri)
            val id=ServiceLocator.reserveDriveFileId(email)
            saved=UploadSession(uri,email,hash,source.size,id,null,System.currentTimeMillis())
            dao.save(saved)
        } else if(confirmed(email,saved,hash)) {
            progress(source.size,source.size)
            return UploadedFile(saved.fileId,true)
        }
        var row=requireNotNull(saved)
        for(restart in 0..1) {
            checkSource()
            var freshSession=false
            if(row.sessionUrl==null) {
                val body=JSONObject(metadata.toString()).put("id",row.fileId)
                val response=api.startUpload(body.toString().toRequestBody(JSON),body.getString("mimeType"),source.size)
                if(response.code()==409) {
                    response.body()?.close(); response.errorBody()?.close()
                    if(confirmed(email,row,hash)) { progress(source.size,source.size); return UploadedFile(row.fileId,true) }
                    throw DriveUploadFailure(409,"fileNotConfirmed")
                }
                if(!response.isSuccessful) throw failure(response)
                val url=response.headers()["Location"]
                response.body()?.close()
                val parsed=url?.let { runCatching { java.net.URI(it) }.getOrNull() }
                if(parsed?.scheme!="https" || parsed.host!="www.googleapis.com") throw DriveUploadFailure(0,"invalidUploadSession")
                row=row.copy(sessionUrl=url,createdAt=System.currentTimeMillis()); dao.save(row)
                freshSession=true
            }
            val session=requireNotNull(row.sessionUrl)
            var offset=0L
            var probe=!freshSession
            var failures=0
            var currentChunkSize=chunkSize
            try {
                while(true) {
                    currentCoroutineContext().ensureActive()
                    checkSource()
                    // The server's offset is authoritative, including after a process restart or timeout.
                    val bytes=if(probe || offset==source.size) ByteArray(0)
                        else source.read(offset,minOf(currentChunkSize.toLong(),source.size-offset).toInt())
                    val range=if(bytes.isEmpty()) "bytes */${source.size}" else "bytes $offset-${offset+bytes.size-1}/${source.size}"
                    val sentAt=android.os.SystemClock.elapsedRealtime()
                    val response=try {
                        api.uploadChunk(session,range,bytes.toRequestBody(if(bytes.isEmpty()) null else metadata.optString("mimeType","application/octet-stream").toMediaType()))
                    } catch(e: IOException) {
                        if(++failures>4) throw UploadConnectionException(e)
                        currentChunkSize=256*1024
                        delay(backoff(failures)); probe=true; continue
                    }
                    when {
                        response.isSuccessful -> {
                            val text=response.body()?.use { it.string() }.orEmpty()
                            response.errorBody()?.close()
                            val ack=runCatching { JSONObject(text) }.getOrNull()
                            val props=ack?.optJSONObject("appProperties")
                            val inlineConfirmed=ack?.optString("id")==row.fileId && ack.optString("size").toLongOrNull()==row.size &&
                                !ack.optBoolean("trashed",false) && props?.optString("sha256")==hash &&
                                props.optString("library")==CloudLibraryRepository.APP_MARKER
                            // Newly created sessions return confirmation fields in the final PUT, avoiding a second GET.
                            // Older saved sessions or an incomplete body still use the explicit confirmation endpoint.
                            if(!inlineConfirmed && !confirmed(email,row,hash)) throw DriveUploadFailure(0,"completionNotConfirmed")
                            progress(source.size,source.size)
                            return UploadedFile(row.fileId,false)
                        }
                        response.code()==308 -> {
                            val header=response.headers()["Range"]
                            val next=if(header==null) 0L else Regex("^bytes=0-([0-9]+)$").matchEntire(header.trim())?.groupValues?.get(1)?.toLongOrNull()?.let { it+1 }
                                ?: throw DriveUploadFailure(0,"invalidUploadOffset")
                            response.body()?.close(); response.errorBody()?.close()
                            if(next !in 0..source.size) throw DriveUploadFailure(0,"invalidUploadOffset")
                            if(!probe && next<=offset) {
                                if(++failures>4) throw UploadConnectionException(IOException("Server did not acknowledge more bytes"))
                                delay(backoff(failures))
                            } else if(next>offset) failures=0
                            if(bytes.isNotEmpty() && next==offset+bytes.size) {
                                val elapsed=(android.os.SystemClock.elapsedRealtime()-sentAt).coerceAtLeast(1)
                                val target=(bytes.size.toLong()*3000/elapsed).coerceIn(256*1024L,maxChunkSize.toLong())
                                val aligned=(target/(256*1024)*(256*1024)).toInt()
                                currentChunkSize=minOf(aligned,currentChunkSize*2).coerceIn(256*1024,maxChunkSize)
                            }
                            offset=next; probe=false
                            progress(offset,source.size)
                        }
                        response.code()==404 || response.code()==410 -> {
                            response.body()?.close(); response.errorBody()?.close()
                            if(confirmed(email,row,hash)) { progress(source.size,source.size); return UploadedFile(row.fileId,true) }
                            throw SessionExpired()
                        }
                        else -> {
                            val error=failure(response)
                            val retryable=error.status in listOf(408,429,500,502,503,504) ||
                                (error.status==403 && error.reason in listOf("rateLimitExceeded","userRateLimitExceeded"))
                            if(!retryable) {
                                // A rejected resumable session must not trap every later Retry on the same URL.
                                if(error.status in 400..499) { row=row.copy(sessionUrl=null); dao.save(row) }
                                if(error.status==400 && restart==0) throw SessionExpired()
                                throw error
                            }
                            if(++failures>4) throw UploadConnectionException(error)
                            delay(backoff(failures)); probe=true
                        }
                    }
                }
            } catch(e: SessionExpired) {
                row=row.copy(sessionUrl=null); dao.save(row)
                if(restart==1) throw DriveUploadFailure(410,"sessionExpired")
            }
        }
        throw DriveUploadFailure(0,"completionNotConfirmed")
    }

    private suspend fun confirmed(email: String, row: UploadSession, hash: String): Boolean {
        val file=try { ServiceLocator.getApiForAccount(email).getFile(row.fileId) }
            catch(e: retrofit2.HttpException) { if(e.code()==404) return false else throw e }
        if(file.trashed || file.size!=row.size || file.appProperties?.get("sha256")!=hash ||
            file.appProperties?.get("library")!=CloudLibraryRepository.APP_MARKER)
            throw DriveUploadFailure(409,"fileMetadataMismatch")
        return true
    }

    companion object {
        private val JSON="application/json; charset=utf-8".toMediaType()
        private fun backoff(attempt: Int)=1000L shl (attempt-1).coerceIn(0,4)
        private fun failure(response: Response<ResponseBody>): DriveUploadFailure {
            val text=response.errorBody()?.use { it.string() }.orEmpty()
            response.body()?.close()
            val raw=runCatching { JSONObject(text).getJSONObject("error").optJSONArray("errors")?.optJSONObject(0)?.optString("reason") }.getOrNull()
            val reason=raw?.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,80}")) } ?: "requestRejected"
            return DriveUploadFailure(response.code(),reason)
        }
    }
}
