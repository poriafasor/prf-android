package com.prf.security.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.prf.security.data.CommandBatch
import java.security.SecureRandom
import com.prf.security.data.CommandResult
import com.prf.security.data.MdmCommand
import com.prf.security.data.PolicyState
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executes commands issued by the owner, and reports the real outcome of each one.
 *
 * The central design decision is that a command which cannot be performed says so.
 * `wipeData` is a no-op for a plain device admin and `resetPassword` is refused
 * unless this app is the device owner, so pretending those succeeded would leave the
 * panel showing a green tick over an action that never happened. Every failure
 * carries a reason, goes back to the server as ok=false, and the server retries it
 * with a backoff — so a command that failed is never silently lost either.
 */
object CommandExecutor {

    private const val TAG = "PRF.Cmd"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Run one batch and ack the outcome of every command individually.
     *
     * Commands run in the order the owner issued them, and the policy that came with
     * the batch is re-applied at the end so that a `set_policy` in the same batch
     * does not land before an `unlock` that was issued after it.
     */
    suspend fun execute(context: Context, batch: CommandBatch) {
        val prefs = Prefs.get(context)
        val api = MdmApi(prefs.serverUrl, prefs.deviceKey)
        val results = mutableListOf<CommandResult>()

        for (cmd in batch.commands) {
            val result = try {
                apply(context, cmd)
            } catch (t: Throwable) {
                Log.w(TAG, "command ${cmd.type} failed: ${t.message}")
                CommandResult(cmd.id, false, t.message ?: "unexpected error")
            }
            results.add(result)
        }

        // The policy always wins last: whatever the batch did, the server's current
        // view of the desired policy is what should be true when we finish.
        PolicyEnforcer.apply(context, batch.policy)

        if (results.isNotEmpty()) api.ack(results)
    }

    private fun apply(context: Context, cmd: MdmCommand): CommandResult {
        return when (cmd.type) {
            "lock" -> guard(context, cmd.id) { lockDevice(context) }
            "unlock" -> guard(context, cmd.id) { unlockDevice(context) }
            "wipe" -> guard(context, cmd.id) { wipeDevice(context) }
            "set_lost" -> guard(context, cmd.id) {
                Prefs.get(context).lostMode = true
                null
            }
            "clear_lost" -> guard(context, cmd.id) {
                Prefs.get(context).lostMode = false
                null
            }
            "block_app" -> guard(context, cmd.id) { setAppBlocked(context, cmd.arg, true) }
            "unblock_app" -> guard(context, cmd.id) { setAppBlocked(context, cmd.arg, false) }
            "set_policy" -> guard(context, cmd.id) { applyPolicyArgument(context, cmd.arg) }
            "release_policy" -> guard(context, cmd.id) { releasePolicyArgument(context, cmd.arg) }
            else -> {
                Log.w(TAG, "unknown command ${cmd.type}")
                CommandResult(cmd.id, false, "unknown command type: ${cmd.type}")
            }
        }
    }

    /**
     * Run the action and turn a null result into success, a thrown message into an
     * honest failure. Anything the action refuses reports the reason rather than a
     * bare "false".
     */
    private inline fun guard(context: Context, id: String, block: () -> String?): CommandResult {
        return try {
            val error = block()
            if (error == null) CommandResult(id, true, "") else CommandResult(id, false, error)
        } catch (t: Throwable) {
            Log.w(TAG, "command $id failed: ${t.message}")
            CommandResult(id, false, t.message ?: "unexpected error")
        }
    }

    // ── the individual actions ────────────────────────────────────────────────

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun adminComponent(context: Context): ComponentName =
        PrfDeviceAdminReceiver.componentName(context)

    private fun isDeviceOwner(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            dpm(context).isDeviceOwnerApp(context.packageName)

    private fun lockDevice(context: Context): String? {
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return "device admin is not enabled"
        dpm(context).lockNow()
        return null
    }

    /**
     * Only the device owner may clear a credential, and only to a PIN it sets itself.
     * On a device where this app is merely an admin, the system refuses the call —
     * so we say that instead of claiming the phone was unlocked.
     */
    private fun unlockDevice(context: Context): String? {
        if (!isDeviceOwner(context)) return "unlock requires this app to be the device owner"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "unlock requires Android 8 or newer"
        return try {
            // resetPassword(password) is a dead end for a device owner: from Android R
            // on, any owner targeting O or above is refused with a SecurityException.
            // The supported path provisions a reset token and uses it, so the token is
            // created once and then reused for every later reset.
            val dpm = dpm(context)
            val admin = PrfDeviceAdminReceiver.componentName(context)
            val token = resetToken(context)
            if (!dpm.isResetPasswordTokenActive(admin)) {
                dpm.setResetPasswordToken(admin, token)
            }
            // The boolean is the real result: false means the new PIN did not satisfy
            // the device's own password constraints, which is not the same as success.
            if (dpm.resetPasswordWithToken(admin, RECOVERY_PIN, token, 0)) {
                null
            } else {
                "the device refused the recovery PIN (it may be too weak for this device)"
            }
        } catch (t: Throwable) {
            t.message ?: "resetPasswordWithToken was refused"
        }
    }

    /**
     * The reset token is a 32-byte secret the device owner provisions once. It is
     * generated on the phone and never leaves it — the platform only checks that the
     * token matches the one it was handed, so prefs are enough, and sending it
     * anywhere would only create a new risk.
     */
    private fun resetToken(context: Context): ByteArray {
        val prefs = Prefs.get(context)
        val hex = prefs.getString(KEY_RESET_TOKEN, "")
        if (hex.length == RESET_TOKEN_BYTES * 2) {
            val parsed = ByteArray(RESET_TOKEN_BYTES) { i ->
                hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
            }
            if (parsed.any { it.toInt() != 0 }) return parsed
        }
        val fresh = ByteArray(RESET_TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.setString(KEY_RESET_TOKEN, fresh.joinToString("") { "%02x".format(it) })
        return fresh
    }

    /**
     * `wipeData` only does anything for a device owner. Calling it as a plain admin
     * returns success from the API while doing nothing at all, so the device-owner
     * check happens here rather than being reported as a successful wipe.
     */
    private fun wipeDevice(context: Context): String? {
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return "device admin is not enabled"
        if (!isDeviceOwner(context)) {
            return "wipe requires this app to be the device owner; as a plain admin it would do nothing"
        }
        return try {
            dpm(context).wipeData(0)
            null
        } catch (t: Throwable) {
            t.message ?: "wipeData was refused"
        }
    }

    /**
     * Block an app by hiding it and blocking its uninstall.
     *
     * Android has no per-app "make this app invisible" for a non-rooted device, so
     * `setApplicationHidden` is the mechanism that actually works, and it requires
     * this app to be the device owner rather than a plain admin. The control app
     * itself is never a valid target: locking the owner's own remote control would
     * be the worst possible outcome.
     *
     * The package is remembered in the policy's blocked list *before* the hide is
     * attempted, and forgotten again if the hide fails. That ordering is the whole
     * fix: `refreshPinnedState` walks that list, and the command never added its
     * target to it, so the app was marked uninstall-blocked and then stayed fully
     * visible and fully usable — the panel reported Contacts blocked while Contacts
     * opened normally. Remembering it also means a later policy re-apply keeps this
     * app hidden instead of quietly bringing it back.
     */
    private fun setAppBlocked(context: Context, pkg: String, blocked: Boolean): String? {
        if (pkg.isBlank()) return "no package name given"
        if (pkg == context.packageName) return "the control app is never blocked"
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return "device admin is not enabled"

        val dpm = dpm(context)
        val admin = adminComponent(context)

        // An admin that is not the device owner cannot hide anything, and would be
        // refused by the system. Say so rather than reporting a block the user can
        // simply walk past.
        val isOwner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            dpm.isDeviceOwnerApp(context.packageName)
        if (!isOwner) {
            return "hiding an app needs this app to be the device owner; as a plain admin it can only block uninstall"
        }

        if (blocked) PolicyEnforcer.rememberExtraBlocked(context, pkg)
        else PolicyEnforcer.forgetExtraBlocked(context, pkg)

        val hidden = PolicyEnforcer.hideNow(context, pkg, blocked)
        if (hidden != null) {
            // A block that did not take must not be left behind in the list, or
            // every later policy re-apply would keep retrying it and the panel
            // would keep showing a block that is not there.
            if (blocked) PolicyEnforcer.forgetExtraBlocked(context, pkg)
            return hidden
        }

        return try {
            dpm.setUninstallBlocked(admin, pkg, blocked)
            null
        } catch (t: Throwable) {
            t.message ?: "could not change the block state of $pkg"
        }
    }

    private fun applyPolicyArgument(context: Context, arg: String): String? {
        val policy = parsePolicy(arg)
            ?: return "set_policy argument was not a JSON object"
        return PolicyEnforcer.apply(context, policy)
    }

    private fun releasePolicyArgument(context: Context, arg: String): String? {
        val seconds = arg.toLongOrNull() ?: return "release_policy argument was not a number of seconds"
        if (seconds < 1 || seconds > 3600) return "release seconds must be between 1 and 3600"
        // The server owns the release window and re-sends the opened policy on every
        // poll. All the device has to do is apply what it was just handed.
        PolicyEnforcer.apply(context, PolicyState())
        return null
    }

    private fun parsePolicy(arg: String): PolicyState? {
        if (arg.isBlank()) return null
        return try {
            val obj = json.parseToJsonElement(arg) as? JsonObject ?: return null
            fun b(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            PolicyState(
                lockTask = b("lockTask"), camera = b("camera"), contacts = b("contacts"),
                calls = b("calls"), sms = b("sms"), gallery = b("gallery"), apps = b("apps"),
                notifications = b("notifications"), uninstall = b("uninstall"),
                wifi = b("wifi"), airplane = b("airplane")
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The PIN a remote unlock sets. The owner changes it from the device; it exists
     * so a lost phone can be opened again from the panel, and is not a secret the
     * panel holds.
     */
    private const val RECOVERY_PIN = "1234"

    /** Prefs key holding the hex reset token, and the token's length in bytes. */
    private const val KEY_RESET_TOKEN = "reset_password_token"
    private const val RESET_TOKEN_BYTES = 32
}
