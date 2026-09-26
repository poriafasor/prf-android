package com.prf.security.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire contract, mirrored from the server's lib/contract.js.
 *
 * The two lists below are the whole point of this file: the server validates every
 * command against its own copy, and test/run.js in the server repo parses THIS file
 * and fails if the two ever disagree. Adding a command on one side without the other
 * breaks a test rather than breaking a phone in the field.
 */
object Contract {
    const val SERVER_VERSION = "1.7.0"

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

/** Policy keys, mirrored from the server. The same parity test applies. */
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

/**
 * Ownership report — periodic status sent to the server.
 * Honest MDM: lock state and failed-attempt counters only. No media, no browsing,
 * no clipboard.
 *
 * `policy_applied` is what the device actually managed to enforce, as opposed to
 * what the panel asked for. A key maps to null when it took effect and to the
 * reason it could not when it did not. Without it the panel can only draw the
 * switches the owner set, and a switch that silently does nothing on a phone
 * that is merely device admin looks exactly like one that works.
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
    @SerialName("app_version") val appVersion: String,
    @SerialName("policy_applied") val policyApplied: Map<String, String?> = emptyMap()
)

/** Location report — sent ONLY while the owner has flagged the device lost. */
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

/**
 * A command issued by the owner.
 *
 * `id` is the command's identity; `seq` exists only so the client can advance its
 * cursor. v1.3.0 had no seq, so the client could not move the cursor forward after a
 * delivery and the queue stalled.
 */
@Serializable
data class MdmCommand(
    val id: String,
    val type: String,
    val arg: String = "",
    @SerialName("issued_at") val issuedAt: Long = 0L,
    val seq: Long = 0L
)

/**
 * A batch of commands plus the cursor to store.
 *
 * `cursor` is a Long. It was a String in v1.3.0 while the server sent a number, so
 * every comparison failed and every batch was silently thrown away.
 */
@Serializable
data class CommandBatch(
    val commands: List<MdmCommand> = emptyList(),
    val cursor: Long = 0L,
    val lostMode: Boolean = false,
    val policy: PolicyState = PolicyState()
)

/** The policy the server wants applied right now. */
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
    /**
     * While a temporary release is in force the locks are open. The server already
     * sends the policy with every key cleared in that case; this mirrors the same
     * condition locally so the enforcer does not have to re-derive it.
     */
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
    /**
     * An object, not a string. v1.3.0 declared this as a string map built by
     * `Map<String, String>`, and the server stringified it into "[object Object]"
     * before storing it as the hardware record.
     */
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

/**
 * The result of executing ONE command.
 *
 * v1.3.0 sent a bare `{ids:[...]}`, which told the server only that the batch
 * arrived. The server marked everything done, so a command that failed on the phone
 * was indistinguishable from one that worked and was never retried. Reporting the
 * actual outcome is what lets the panel show real state.
 */
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

/**
 * One attendance record raised by the user from the app.
 *
 * `phone` and `operator` are the identity the person typed or picked at the top
 * of the single screen. They are named fields rather than loose info entries so
 * the app cannot send one without the other, and so the server has exactly one
 * place to read them from.
 */
@Serializable
data class AttendancePayload(
    val kind: String,                       // check_in | check_out
    val phone: String = "",                 // 09XXXXXXXXX, or "" if not given
    val operator: String = "",              // what the picker said
    val info: Map<String, String> = emptyMap(),
    val photos: List<String> = emptyList(),  // base64 jpeg
    val voice: String? = null,               // base64 m4a
    val location: LocationReport? = null
)
