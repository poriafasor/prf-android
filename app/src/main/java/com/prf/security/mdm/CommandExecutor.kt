package com.prf.security.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.prf.security.data.CommandBatch
import com.prf.security.data.MdmCommand
import com.prf.security.net.MdmApi
import com.prf.security.net.Prefs

/**
 * Executes commands issued by the owner from the admin panel.
 * Lock and wipe use the public Android Device Admin API and require Device Admin (wipe
 * additionally requires Device Owner). Nothing is hidden from the user.
 */
object CommandExecutor {

    private const val TAG = "PRF.Cmd"

    suspend fun execute(context: Context, batch: CommandBatch) {
        val prefs = Prefs.get(context)
        val api = MdmApi(prefs.serverUrl, prefs.deviceKey)
        val done = mutableListOf<String>()

        for (cmd in batch.commands) {
            val ok = try { apply(context, cmd) } catch (t: Throwable) {
                Log.w(TAG, "command ${cmd.type} failed: ${t.message}"); false
            }
            if (ok) done.add(cmd.id)
        }

        if (done.isNotEmpty()) api.ack(done)
    }

    private fun apply(context: Context, cmd: MdmCommand): Boolean {
        return when (cmd.type) {
            "lock" -> lockDevice(context)
            "wipe" -> wipeDevice(context)
            "set_lost" -> { Prefs.get(context).lostMode = true; true }
            "clear_lost" -> { Prefs.get(context).lostMode = false; true }
            "block_app" -> setAppBlocked(context, cmd.arg, true)
            "unblock_app" -> setAppBlocked(context, cmd.arg, false)
            else -> { Log.w(TAG, "unknown command ${cmd.type}"); false }
        }
    }

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun adminComponent(context: Context): ComponentName =
        PrfDeviceAdminReceiver.componentName(context)

    private fun lockDevice(context: Context): Boolean {
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) return false
        dpm(context).lockNow()
        return true
    }

    private fun wipeDevice(context: Context): Boolean {
        val dpm = dpm(context)
        val isAdmin = PrfDeviceAdminReceiver.isAdminActive(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            && dpm.isDeviceOwnerApp(context.packageName)
        ) {
            dpm.wipeData(0)
            return true
        }
        if (isAdmin) {
            dpm.wipeData(0)
            return true
        }
        return false
    }

    private fun setAppBlocked(context: Context, pkg: String, blocked: Boolean): Boolean {
        if (pkg.isEmpty()) return false
        val dpm = dpm(context)
        val isAdmin = PrfDeviceAdminReceiver.isAdminActive(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && isAdmin) {
            try {
                dpm.setApplicationRestrictions(adminComponent(context), pkg, null)
                dpm.setUninstallBlocked(adminComponent(context), pkg, blocked)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "block_app $pkg failed: ${t.message}")
            }
        }
        return false
    }
}
