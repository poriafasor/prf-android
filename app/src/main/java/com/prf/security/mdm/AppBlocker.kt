package com.prf.security.mdm

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.ContactsContract
import android.util.Log


































object AppBlocker {

    private const val TAG = "PRF.Block"

    






    enum class Level {
        
        SUSPENDED,

        
        HIDDEN,

        
        UNINSTALL_BLOCKED,

        
        NONE,
    }

    







    data class BlockResult(
        val pkg: String,
        val level: Level,
        val reason: String? = null,
    ) {
        
        val isRealLock: Boolean get() = level == Level.SUSPENDED

        
        fun describe(): String = when (level) {
            Level.SUSPENDED -> "$pkg تعلیق شد و حتی از فایل‌منیجر یا لینک هم باز نمی‌شود."
            Level.HIDDEN ->
                "$pkg فقط از فایلی صفحه پنهان شد؛ از فایل‌منیجر، لینک یا «باز کردن با» هنوز " +
                    "قابل دسترسی است. برای مسدودی کامل، این گوشی باید اندروید ۹ یا بالاتر باشد."
            Level.UNINSTALL_BLOCKED ->
                "$pkg فقط از حذف محافظت شد و کاملاً قابل استفاده است."
            Level.NONE -> reason ?: "$pkg مسدود نشد."
        }
    }

    

    














    private fun resolveTargets(context: Context, key: String): List<String> {
        val pm = context.packageManager
        val resolved = mutableSetOf<String>()

        @Suppress("DEPRECATION")
        fun fromIntent(intent: Intent) {
            try {
                
                
                
                
                
                
                
                
                
                
                
                
                val flags = 0
                pm.resolveActivity(intent, flags)?.activityInfo?.packageName?.let { resolved.add(it) }
                pm.queryIntentActivities(intent, flags)
                    .forEach { resolved.add(it.activityInfo.packageName) }
            } catch (t: Throwable) {
                
                
                Log.w(TAG, "could not resolve $key: ${t.message}")
            }
        }

        when (key) {
            "camera" -> fromIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "gallery" -> fromIntent(
                Intent(Intent.ACTION_VIEW)
                    .setType("image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            "contacts" -> fromIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse("content://${ContactsContract.Contacts.CONTENT_URI}")),
            )
            "calls" -> fromIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:0")))
            "sms" -> fromIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:0")))
        }

        
        
        
        return (resolved + KNOWN[ key].orEmpty()).toList()
    }

    
    private val KNOWN = mapOf(
        "camera" to listOf(
            "com.android.camera2", "com.android.camera", "com.sec.android.app.camera",
            "com.htc.camera", "com.motorola.camera2", "com.oneplus.camera",
            "com.oppo.camera", "com.vivo.camera", "com.huawei.camera",
        ),
        "gallery" to listOf(
            "com.google.android.apps.photos", "com.sec.android.gallery3d",
            "com.android.gallery3d", "com.miui.gallery", "com.oneplus.gallery",
            "com.coloros.gallery3d", "com.vivo.gallery", "com.huawei.photos",
            
            
            
            "com.android.documentsui", "com.google.android.documentsui",
        ),
        "contacts" to listOf(
            "com.android.contacts", "com.google.android.contacts",
            "com.sec.android.contacts", "com.miui.contacts",
        ),
        "calls" to listOf("com.android.dialer", "com.google.android.dialer", "com.sec.android.dialer"),
        "sms" to listOf(
            "com.android.mms", "com.google.android.apps.messaging",
            "com.android.messaging", "com.samsung.android.messaging",
        ),
    )

    







    private fun forbidden(context: Context, pkg: String): String? = when {
        pkg == context.packageName -> "برنامه‌ی کنترلی هرگز مسدود نمی‌شود."
        pkg == activeLauncher(context) -> "صفحه‌ی اصلی گوشی مسدود نمی‌شود؛ با مسدود شدنش گوشی از کار می‌افتد."
        pkg == "com.android.settings" || pkg == "com.android.settings.intelligence" ->
            "تنظیمات گوشی مسدود نمی‌شود؛ تنها جایی است که کاربر می‌تواند ببیند چه شده."
        else -> null
    }

    private fun activeLauncher(context: Context): String? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    } catch (t: Throwable) {
        null
    }

    

    













    fun block(context: Context, pkg: String): BlockResult {
        forbidden(context, pkg)?.let { return BlockResult(pkg, Level.NONE, it) }

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = PrfDeviceAdminReceiver.componentName(context)
        val isOwner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            dpm.isDeviceOwnerApp(context.packageName)

        if (!isOwner) {
            return BlockResult(
                pkg, Level.NONE,
                "پنهان‌کردن و تعلیق برنامه فقط برای Device Owner ممکن است؛ این برنامه روی این " +
                    "گوشی فقط Device Admin است. باید گوشی از نو راه‌اندازی شود و با همین برنامه " +
                    "به‌عنوان مالک دستگاه ثبت شود.",
            )
        }

        val failures = mutableListOf<String>()
        var level = Level.NONE

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val suspendErr = try {
                
                
                
                
                
                
                val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(pkg), true)
                when {
                    notSuspended == null -> null
                    notSuspended.isEmpty() -> null
                    pkg in notSuspended -> "سیستم‌عامل تعلیق $pkg را نپذیرفت و برنامه هنوز باز می‌شود."
                    else -> null
                }
            } catch (t: Throwable) {
                "تعلیق $pkg رد شد: ${t.message}"
            }
            if (suspendErr == null) {
                level = Level.SUSPENDED
            } else {
                failures.add(suspendErr)
            }
        } else {
            failures.add("تعلیق برنامه از اندروید ۹ اضافه شده و روی این گوشی کار نمی‌کند.")
        }

        
        
        val uninstallErr = try {
            dpm.setUninstallBlocked(admin, pkg, true)
            null
        } catch (t: Throwable) {
            "محافظت از حذف $pkg رد شد: ${t.message}"
        }
        if (uninstallErr != null) failures.add(uninstallErr)
        else if (level == Level.NONE) level = Level.UNINSTALL_BLOCKED

        
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val err = try {
                dpm.setApplicationHidden(admin, pkg, true)
                null
            } catch (t: Throwable) {
                "پنهان‌کردن $pkg رد شد: ${t.message}"
            }
            if (err != null) failures.add(err)
            else if (level == Level.NONE) level = Level.HIDDEN
        }

        if (level == Level.NONE) {
            return BlockResult(pkg, Level.NONE, failures.firstOrNull() ?: "سیستم هیچ‌کدام را نپذیرفت.")
        }
        return BlockResult(pkg, level).also {
            
            
            
            failures.forEach { Log.w(TAG, "$pkg partially blocked: $it") }
        }
    }

    







    fun unblock(context: Context, pkg: String): BlockResult {
        forbidden(context, pkg)?.let { return BlockResult(pkg, Level.NONE, it) }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = PrfDeviceAdminReceiver.componentName(context)
        if (!PrfDeviceAdminReceiver.isAdminActive(context)) {
            return BlockResult(pkg, Level.NONE, "دسترسی مدیریتی روی این گوشی فعال نیست.")
        }

        val failures = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
            } catch (t: Throwable) {
                failures.add("برگرداندن تعلیق $pkg رد شد: ${t.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                dpm.setApplicationHidden(admin, pkg, false)
            } catch (t: Throwable) {
                failures.add("برگرداندن $pkg به صفحه‌ی اصلی رد شد: ${t.message}")
            }
        }
        try {
            dpm.setUninstallBlocked(admin, pkg, false)
        } catch (t: Throwable) {
            failures.add("برداشتن محافظت حذف از $pkg رد شد: ${t.message}")
        }

        return if (failures.isEmpty()) BlockResult(pkg, Level.NONE)
        else BlockResult(pkg, Level.NONE, failures.first())
    }

    






    fun blockKey(context: Context, key: String): Map<String, String?> {
        val targets = resolveTargets(context, key)
        if (targets.isEmpty()) return mapOf(key to "هیچ برنامه‌ای روی این گوشی برای این کار پیدا نشد.")

        val results = targets.map { block(context, it) }
        val realLocks = results.filter { it.isRealLock }
        val anyLevel = results.filter { it.level != Level.NONE }

        return when {
            realLocks.size == results.size -> mapOf(key to null)
            anyLevel.isNotEmpty() -> {
                val stuck = results.filter { it.level == Level.NONE }
                val hiddenOnly = results.count { it.level == Level.HIDDEN }
                mapOf(
                    key to buildString {
                        append("مسدودی کامل نشد. ")
                        if (hiddenOnly > 0) {
                            append("$hiddenOnly برنامه فقط از فایلی صفحه پنهان شد ")
                            append("و از فایل‌منیجر یا لینک همچنان باز می‌شود. ")
                        }
                        if (stuck.isNotEmpty()) {
                            append("مسدود نشد: ")
                            append(stuck.joinToString("، ") { "${it.pkg} (${it.reason})" })
                            append(".")
                        }
                    },
                )
            }
            else -> mapOf(key to (results.first().reason ?: "سیستم مسدودی را نپذیرفت."))
        }
    }

    
    fun unblockKey(context: Context, key: String) {
        for (pkg in resolveTargets(context, key)) {
            unblock(context, pkg).reason?.let { Log.w(TAG, "unblock $pkg: $it") }
        }
    }

    









}
