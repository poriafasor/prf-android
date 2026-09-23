package com.prf.security.data

import kotlinx.serialization.Serializable

/**
 * One captured photo staged for upload to prf-database.
 * @param localPath absolute path of the JPEG inside app-private storage
 * @param repoPath  target path in the database repo, e.g. "3a7b9f1c2d4e5f60/images/2026-09-21/front_1.jpg"
 */
@Serializable
data class PhotoPayload(
    val localPath: String,
    val repoPath: String,
)

/**
 * One recorded voice clip staged for upload to prf-database.
 * @param localPath absolute path of the audio file inside app-private storage
 * @param repoPath  target path in the database repo, e.g. "3a7b9f1c2d4e5f60/voice/2026-09-22_10-30-00_attendance.m4a"
 */
@Serializable
data class VoicePayload(
    val localPath: String,
    val repoPath: String,
)

/**
 * Everything that makes up one check-in. Serialized and persisted offline until every
 * file in it has landed in prf-database, so a kill or a network drop never loses data.
 *
 * @param infoLocalPath  app-private copy of `user info.txt` staged at capture time;
      the worker re-reads it so the upload is not tied to the file still being on disk.
@param markerRepoPath  repo path of the `.no-media` marker written when the user
 *    declined photo capture. Git cannot store an empty directory, so a check-in with no
 *    photos and no marker would leave the date folder invisible to the panel. Null when
 *    consent was given and real photos were staged.
 * @param markerLocalPath app-private copy of that marker, null when there is no marker.
 */
@Serializable
data class CheckIn(
    val id: String,
    val androidId: String,
    val date: String,
    val timestampMs: Long,
    val consent: Boolean,
    val infoRepoPath: String,
    val infoLocalPath: String,
    val photos: List<PhotoPayload> = emptyList(),
    val voices: List<VoicePayload> = emptyList(),
    val markerRepoPath: String? = null,
    val markerLocalPath: String? = null,
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
