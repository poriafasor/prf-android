package com.prf.security.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ownership report - sent to the server periodically.
 * Honest MDM: lock state and failed-attempt counters only. No media, no browsing, no clipboard.
 */
@Serializable
data class OwnershipReport(
    val id: String,
    @SerialName("android_id") val androidId: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val locked: Boolean,
    val lost: Boolean,
    val secured: Boolean,
    @SerialName("failed_attempts") val failedAttempts: Int,
    @SerialName("screen_on") val screenOn: Boolean,
    @SerialName("event_triggered") val eventTriggered: String,
    @SerialName("app_version") val appVersion: String
)

/** Location report - sent ONLY when the device is flagged lost by the owner. */
@Serializable
data class LocationReport(
    val id: String,
    @SerialName("android_id") val androidId: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val maps: String,
    val geo: String,
    @SerialName("plus_code") val plusCode: String,
    val raw: String
)

/** A pending command issued by the owner from the admin panel. */
@Serializable
data class MdmCommand(
    val id: String,
    val type: String,        // lock | wipe | set_lost | clear_lost | block_app | unblock_app
    val arg: String = "",    // package name for block_app / unblock_app
    @SerialName("issued_at") val issuedAt: Long
)

@Serializable
data class CommandBatch(
    val commands: List<MdmCommand> = emptyList(),
    val cursor: String = ""
)

@Serializable
data class DeviceRegistration(
    @SerialName("android_id") val androidId: String,
    val hardware: Map<String, String>,
    val label: String
)
