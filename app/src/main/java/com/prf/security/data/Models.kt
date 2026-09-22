package com.prf.security.data

import kotlinx.serialization.Serializable

/**
 * One captured photo staged for upload to prf-database.
 * @param localPath absolute path of the JPEG inside app-private storage
 * @param repoPath  target path in the database repo, e.g. "3a7b9f1c2d4e5f60/2026-09-21/images/front_1.jpg"
 */
@Serializable
data class PhotoPayload(
    val localPath: String,
    val repoPath: String,
)

/**
 * Everything that makes up one check-in. Serialized and persisted offline until every
 * file in it has landed in prf-database, so a kill or a network drop never loses data.
 */
@Serializable
data class CheckIn(
    val id: String,
    val androidId: String,
    val date: String,
    val timestampMs: Long,
    val consent: Boolean,
    val infoRepoPath: String,
    val photos: List<PhotoPayload> = emptyList(),
    val voices: List<VoicePayload> = emptyList(),
)

/**
 * One recorded voice clip staged for upload to prf-database.
 * @param localPath absolute path of the audio file inside app-private storage
 * @param repoPath  target path in the database repo, e.g. "Voices/2026-09-22_10-30-00_attendance.m4a"
 */
@Serializable
data class VoicePayload(
    val localPath: String,
    val repoPath: String,
)

/**
 * A staged capture session: the info file plus the JPEGs, ready to be turned into a
 * [CheckIn] and pushed onto the offline queue.
 */
data class CaptureResult(
    val id: String,
    val androidId: String,
    val date: String,
    val infoFile: java.io.File,
    val imageFiles: List<java.io.File>,
)
