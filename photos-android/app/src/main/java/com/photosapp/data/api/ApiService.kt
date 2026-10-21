package com.photosapp.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Url
import retrofit2.http.Path
import retrofit2.http.PATCH
import retrofit2.http.DELETE

data class DriveAboutResponse(
    val storageQuota: StorageQuota
)

data class StorageQuota(
    val limit: Long?,
    val usage: Long,
    val usageInDrive: Long,
    val usageInDriveTrash: Long
)

data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null
)

data class DriveFile(
    val id: String,
    val name: String,
    val thumbnailLink: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val createdTime: String? = null,
    val appProperties: Map<String, String>? = null,
    val trashed: Boolean = false,
    val imageMediaMetadata: DriveImageMetadata? = null,
    val videoMediaMetadata: DriveVideoMetadata? = null
)

data class DriveImageMetadata(val width: Int? = null, val height: Int? = null, val cameraMake: String? = null,
    val cameraModel: String? = null, val time: String? = null)
data class DriveVideoMetadata(val width: Int? = null, val height: Int? = null, val durationMillis: Long? = null)
data class DriveStartToken(val startPageToken: String)
data class DriveGeneratedIds(val ids: List<String>)
data class DriveChange(val fileId: String, val removed: Boolean = false, val file: DriveFile? = null)
data class DriveChanges(val changes: List<DriveChange> = emptyList(), val nextPageToken: String? = null, val newStartPageToken: String? = null)

data class UserInfoResponse(
    val email: String
)

interface ApiService {
    @GET("drive/v3/files/{fileId}?alt=media") suspend fun albumDocument(@Path("fileId") id: String): okhttp3.ResponseBody
    @POST("upload/drive/v3/files?uploadType=multipart&fields=id") suspend fun createAlbumDocument(@Body body: RequestBody): DriveFile
    @PATCH("upload/drive/v3/files/{fileId}?uploadType=multipart&fields=id") suspend fun writeAlbumDocument(@Path("fileId") id: String, @Body body: RequestBody): DriveFile
    @GET("drive/v3/files/generateIds?space=drive&type=files&fields=ids") suspend fun generateFileId(@Query("count") count: Int = 16): DriveGeneratedIds
    @GET("drive/v3/changes/startPageToken") suspend fun startPageToken(): DriveStartToken
    @GET("drive/v3/changes") suspend fun changes(@Query("pageToken") pageToken: String,
        @Query("fields") fields: String = "nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,mimeType,size,createdTime,thumbnailLink,appProperties,trashed))",
        @Query("pageSize") pageSize: Int = 1000): DriveChanges
    @DELETE("drive/v3/files/{fileId}") suspend fun deleteFile(@Path("fileId") fileId: String): retrofit2.Response<okhttp3.ResponseBody>

    // User info to get the email address of the connected account
    @GET("oauth2/v2/userinfo")
    suspend fun getUserInfo(): UserInfoResponse

    // Get storage quota
    @GET("drive/v3/about?fields=storageQuota")
    suspend fun getDriveAbout(): DriveAboutResponse

    @GET("drive/v3/files/{fileId}?fields=id,name,trashed,size,mimeType,createdTime,appProperties,imageMediaMetadata(width,height,cameraMake,cameraModel,time),videoMediaMetadata")
    suspend fun getFile(@Path("fileId") fileId: String): DriveFile

    @PATCH("drive/v3/files/{fileId}?fields=id,name,appProperties")
    suspend fun updateFileMetadata(@Path("fileId") fileId: String, @Body metadata: RequestBody): DriveFile

    // Search for a file in the appDataFolder or root (we will use root for now, or a specific folder)
    @GET("drive/v3/files")
    suspend fun searchFiles(
        @Query("q") query: String,
        @Query("fields") fields: String = "nextPageToken,files(id,name,mimeType,size,createdTime,thumbnailLink,appProperties,trashed)",
        @Query("pageToken") pageToken: String? = null,
        @Query("pageSize") pageSize: Int = 1000
    ): DriveFileList

    @POST("upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,size,appProperties,trashed")
    suspend fun uploadDerivative(@Body body: RequestBody): DriveFile

    @POST("upload/drive/v3/files?uploadType=resumable&fields=id,name,size,appProperties,trashed")
    suspend fun startUpload(
        @Body metadata: RequestBody,
        @Header("X-Upload-Content-Type") mimeType: String,
        @Header("X-Upload-Content-Length") size: Long
    ): retrofit2.Response<okhttp3.ResponseBody>

    @PUT
    suspend fun uploadChunk(
        @Url sessionUrl: String,
        @Header("Content-Range") range: String,
        @Body bytes: RequestBody
    ): retrofit2.Response<okhttp3.ResponseBody>

    // Create a folder
    @Multipart
    @POST("drive/v3/files")
    suspend fun createFolder(
        @Part("metadata") metadata: RequestBody
    ): DriveFile

    // Upload a file directly to Google Drive
    @Multipart
    @POST("upload/drive/v3/files?uploadType=multipart")
    suspend fun uploadFile(
        @Part("metadata") metadata: RequestBody,
        @Part file: MultipartBody.Part
    ): DriveFile
}
