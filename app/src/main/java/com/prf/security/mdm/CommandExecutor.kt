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











object CommandExecutor {

    private const val TAG = "PRF.Cmd"
    private val json = Json { ignoreUnknownKeys = true }

    






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

    




    private inline fun guard(context: Context, id: String, block: () -> String?): CommandResult {
        return try {
            val error = block()
            if (error == null) CommandResult(id, true, "") else CommandResult(id, false, error)
        } catch (t: Throwable) {
            Log.w(TAG, "command $id failed: ${t.message}")
            CommandResult(id, false, t.message ?: "unexpected error")
        }
    }

    

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

    




    private fun unlockDevice(context: Context): String? {
        if (!isDeviceOwner(context)) return "unlock requires this app to be the device owner"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "unlock requires Android 8 or newer"
        return try {
            
            
            
            
            val dpm = dpm(context)
            val admin = PrfDeviceAdminReceiver.componentName(context)
            val token = resetToken(context)
            if (!dpm.isResetPasswordTokenActive(admin)) {
                dpm.setResetPasswordToken(admin, token)
            }
            
            
            if (dpm.resetPasswordWithToken(admin, RECOVERY_PIN, token, 0)) {
                null
            } else {
                "the device refused the recovery PIN (it may be too weak for this device)"
            }
        } catch (t: Throwable) {
            t.message ?: "resetPasswordWithToken was refused"
        }
    }

    





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

    














    private fun setAppBlocked(context: Context, pkg: String, blocked: Boolean): String? {
        if (pkg.isBlank()) return "no package name given"
        if (pkg == context.packageName) return "the control app is never blocked"
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return "device admin is not enabled"

        
        
        
        if (!isDeviceOwner(context)) {
            return "suspending or hiding an app needs this app to be the device owner; " +
                "as a plain admin it can only block uninstall, which does not lock anything"
        }

        return PolicyEnforcer.setBlocked(context, pkg, blocked)
    }

    private fun applyPolicyArgument(context: Context, arg: String): String? {
        val policy = parsePolicy(arg)
            ?: return "set_policy argument was not a JSON object"
        return PolicyEnforcer.apply(context, policy)
    }

    private fun releasePolicyArgument(context: Context, arg: String): String? {
        val seconds = arg.toLongOrNull() ?: return "release_policy argument was not a number of seconds"
        if (seconds < 1 || seconds > 3600) return "release seconds must be between 1 and 3600"
        
        
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

    




    private const val RECOVERY_PIN = "1234"

    
    private const val KEY_RESET_TOKEN = "reset_password_token"
    private const val RESET_TOKEN_BYTES = 32
}
