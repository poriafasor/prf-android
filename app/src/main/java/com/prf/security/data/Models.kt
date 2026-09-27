package com.prf.security.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable









object Contract {
    const val SERVER_VERSION = "2.1.0"

    val COMMANDS = listOf(
        "lock",
        "unlock",
        "wipe",
        "set_lost",
        "clear_lost",
        "block_app",
        "unblock_app",
        "set_policy",
        "release_policy",
    )
}


object PolicyKeys {
    val ALL = listOf(
        "lockTask",
        "camera",
        "contacts",
        "calls",
        "sms",
        "gallery",
        "apps",
        "notifications",
        "uninstall",
        "wifi",
        "airplane",
    )
}












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
    @SerialName("app_version") val appVersion: String,
    @SerialName("policy_applied") val policyApplied: Map<String, String?> = emptyMap()
)


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








@Serializable
data class MdmCommand(
    val id: String,
    val type: String,
    val arg: String = "",
    @SerialName("issued_at") val issuedAt: Long = 0L,
    val seq: Long = 0L
)







@Serializable
data class CommandBatch(
    val commands: List<MdmCommand> = emptyList(),
    val cursor: Long = 0L,
    val lostMode: Boolean = false,
    val policy: PolicyState = PolicyState()
)


@Serializable
data class PolicyState(
    @SerialName("lockTask") val lockTask: Boolean = false,
    val camera: Boolean = false,
    val contacts: Boolean = false,
    val calls: Boolean = false,
    val sms: Boolean = false,
    val gallery: Boolean = false,
    val apps: Boolean = false,
    val notifications: Boolean = false,
    val uninstall: Boolean = false,
    val wifi: Boolean = false,
    val airplane: Boolean = false,
    @SerialName("tempReleaseUntil") val tempReleaseUntil: Long = 0L
) {
    




    fun isReleased(nowMs: Long): Boolean = tempReleaseUntil > 0L && nowMs < tempReleaseUntil

    fun activeKeys(): List<String> = PolicyKeys.ALL.filter { valueOf(it) }

    fun valueOf(key: String): Boolean = when (key) {
        "lockTask" -> lockTask
        "camera" -> camera
        "contacts" -> contacts
        "calls" -> calls
        "sms" -> sms
        "gallery" -> gallery
        "apps" -> apps
        "notifications" -> notifications
        "uninstall" -> uninstall
        "wifi" -> wifi
        "airplane" -> airplane
        else -> false
    }
}

@Serializable
data class DeviceRegistration(
    @SerialName("android_id") val androidId: String,
    




    val hardware: Map<String, String>,
    val label: String
)

@Serializable
data class RegisterResponse(
    val deviceKey: String = "",
    val rotated: Boolean = false,
    val serverVersion: String = "",
    val policy: PolicyState = PolicyState(),
    val lostMode: Boolean = false
)

@Serializable
data class ReportResponse(
    val ok: Boolean = false,
    val accepted: Int = 0,
    val lostMode: Boolean = false,
    val locationAccepted: Boolean = false,
    val policy: PolicyState = PolicyState()
)









@Serializable
data class CommandResult(
    val id: String,
    val ok: Boolean,
    val error: String = ""
)

@Serializable
data class AckResponse(
    val ok: Boolean = false,
    val accepted: Int = 0
)









@Serializable
data class AttendancePayload(
    val kind: String,                       
    val phone: String = "",                 
    val operator: String = "",              
    val info: Map<String, String> = emptyMap(),
    val photos: List<String> = emptyList(),  
    val voice: String? = null,               
    val location: LocationReport? = null
)
