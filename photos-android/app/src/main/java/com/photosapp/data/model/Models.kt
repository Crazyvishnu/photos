package com.photosapp.data.model

import com.google.gson.annotations.SerializedName

// ─── Requests ─────────────────────────────────────────────────────────────────

data class CheckHashesRequest(val hashes: List<String>)

// ─── Responses ────────────────────────────────────────────────────────────────

data class ConnectUrlResponse(val url: String)

data class AccountStatusResponse(
    val googleEmail: String,
    val sessionId: String,
    val quota: DriveQuota?,
)

data class DriveQuota(
    val used: Long,
    val limit: Long?,
    val free: Long?,
)

data class CheckHashesResponse(
    @SerializedName("new") val newHashes: List<String>
)

data class UploadResponse(
    val alreadyExists: Boolean,
    val item: MediaItemDto?,
)

data class MediaItemsResponse(
    val items: List<MediaItemDto>,
    val nextCursor: String?,
    val hasNextPage: Boolean,
)

data class MediaItemDto(
    val id: String,
    val sha256Hash: String,
    val driveFileId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTaken: String?,
    val source: String,
    val hasThumbnail: Boolean,
    val width: Int?,
    val height: Int?,
    val durationMs: Int?,
    val createdAt: String,
)
