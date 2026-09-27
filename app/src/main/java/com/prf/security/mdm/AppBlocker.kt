package com.prf.security.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.ContactsContract
import android.util.Log

/**
 * Makes a locked app genuinely unopenable, rather than merely invisible.
 *
 * The reason this file exists at all: v1.7.0 "locked" the gallery with
 * `setApplicationHidden` and nothing else, and that is the one call
 * DevicePolicyManager has for hiding. It hides the *icon*. It does not stop the
 * app from running. The launcher, the Files app, a `content://` link in a
 * browser, a share sheet, or the system's own "open with" menu all reach the
 * gallery anyway, so a switch in the panel could be green while the user walked
 * straight into the thing the owner had just locked. That is the specific
 * failure this project's governing rule exists to prevent.
 *
 * There are three mechanisms here, and they are not equal:
 *
 *  - **suspend** — `setPackagesSuspended`, Android 9+, device owner. The app
 *    cannot be launched at all; the system shows "suspended by your
 *    administrator" if anything tries. This is the only one that is a real lock.
 *  - **hide** — `setApplicationHidden`, Android 8+, device owner. Removes it
 *    from the launcher. A launcher-level inconvenience, not a lock.
 *  - **uninstall-block** — `setUninstallBlocked`, Android 5+. Keeps the app
 *    from being removed so a lock cannot be undone by uninstalling it.
 *
 * All three are applied, strongest first. What is reported back is not "done" or
 * "failed" but *which* of them took, because the difference is the whole point:
 * a caller that says "locked" when only the icon was hidden is telling the
 * owner a lie that shows up five minutes later when the user opens the gallery
 * from their file manager.
 *
 * The device-owner requirement is real and is not worked around. Without it the
 * system throws on the first call, and that refusal is returned as a reason
 * rather than swallowed — a plain admin genuinely cannot do this, and the panel
 * is told so.
 */
object AppBlocker {

    private const val TAG = "PRF.Block"

    /**
     * How a package is blocked right now.
     *
     * [level] is what actually took effect, not what was attempted, so the panel
     * can distinguish a phone where the gallery cannot be opened from one where
     * the icon merely disappeared.
     */
    enum class Level {
        /** `setPackagesSuspended` took: the app cannot be launched at all. */
        SUSPENDED,

        /** Only the launcher icon was removed; other entry points still work. */
        HIDDEN,

        /** Only the uninstall was blocked; the app is entirely usable. */
        UNINSTALL_BLOCKED,

        /** Nothing took. [BlockResult.reason] says why. */
        NONE,
    }

    /**
     * The outcome for one package, and the reason when nothing worked.
     *
     * [refusals] keeps every individual failure rather than only the last one,
     * because the first refusal is usually the informative one: "not device
     * owner" tells the owner to fix the phone, while a null-pointer from an OEM
     * build does not.
     */
    data class BlockResult(
        val pkg: String,
        val level: Level,
        val reason: String? = null,
    ) {
        /** True only for a lock that actually prevents the app being opened. */
        val isRealLock: Boolean get() = level == Level.SUSPENDED

        /** A sentence for the panel, saying exactly what is true right now. */
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

    // ── target resolution ───────────────────────────────────────────────────

    /**
     * Packages that can perform a given action on *this* phone.
     *
     * The v1.7.0 lists were a hardcoded table of package names per brand
     * (com.sec.android.gallery3d, com.miui.gallery, …). That guess is wrong the
     * moment a phone ships a different app, a user installs a replacement, or an
     * OEM renames things — and a target list that silently misses the real
     * gallery is a lock on nothing.
     *
     * So the targets are resolved from the device instead: ask the package
     * manager which app currently handles the intent, and take that alongside
     * the known-brand names. Resolution is the part that adapts; the table stays
     * only so that a *non-default* image of the same app is covered too, since
     * the user can switch the default handler in Settings and keep using it.
     */
    private fun resolveTargets(context: Context, key: String): List<String> {
        val pm = context.packageManager
        val resolved = mutableSetOf<String>()

        @Suppress("DEPRECATION")
        fun fromIntent(intent: Intent) {
            try {
                // resolveActivity answers "what would open this right now"; the
                // second query answers "what *could* open this", which is what
                // matters for a lock — the user can still pick a different app
                // from the chooser even when it is not the default.
                //
                // The int-flags overloads are deprecated from API 33 in favour of
                // the PackageManager.ResolveInfoFlags ones, but they still exist
                // and still work, and the flags form is a compileSdk 34 concern
                // rather than a runtime one: a phone that needs the new form is
                // running a build whose resolver behaves identically here. The
                // alternative is a version branch around a call that returns the
                // same list either way.
                val flags = 0
                pm.resolveActivity(intent, flags)?.activityInfo?.packageName?.let { resolved.add(it) }
                pm.queryIntentActivities(intent, flags)
                    .forEach { resolved.add(it.activityInfo.packageName) }
            } catch (t: Throwable) {
                // A device with no handler for the intent is not an error here;
                // there is simply nothing to block for that key.
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

        // Resolved apps plus the brand table: a user who set a third-party file
        // viewer as default still has the stock gallery installed and still has
        // it in recents.
        return (resolved + KNOWN[ key].orEmpty()).toList()
    }

    /** Stock app names per action, as a floor under the resolved list. */
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
            // File browsers reach the same pictures, and on most phones the Files
            // app is the shorter way in: hiding the gallery while leaving Files
            // open is not a lock.
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

    /**
     * Packages this app must never block, whatever it is asked to block.
     *
     * Blocking the launcher or this app would leave the owner with no way to
     * undo the block, and blocking Settings would remove the only place the user
     * could go looking for why their phone changed. `setPackagesSuspended` on
     * the launcher is not a policy the panel should be able to express.
     */
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

    // ── the three mechanisms ────────────────────────────────────────────────

    /**
     * Block one package, applying every mechanism the device supports.
     *
     * The order is strength-first on purpose. Suspend is the only call that
     * stops the app running, so it is tried first; hiding and the uninstall
     * block are applied afterwards regardless, because they are what keeps a
     * successfully suspended app from being removed and reinstalled.
     *
     * One refusal never stops the rest. A phone that refuses `setPackagesSuspended`
     * (Android 8, or an OEM build that throws) still gets the hide and the
     * uninstall block, and the result says so — which is strictly better than
     * the v1.7.0 behaviour, where a throw from the single call left the package
     * completely untouched and the panel reported nothing.
     */
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

        // 1. suspend — the only real lock. Android 9+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val err = try {
                dpm.setPackagesSuspended(admin, arrayOf(pkg), true, arrayOf(pkg))
                null
            } catch (t: Throwable) {
                "تعلیق $pkg رد شد: ${t.message}"
            }
            if (err == null) {
                level = Level.SUSPENDED
            } else {
                failures.add(err)
                // An OEM build can accept the call and not honour it.
                // isPackagesSuspended is the only way to find out, and believing
                // the call instead of checking it is how a lock reports success
                // while doing nothing.
                val reallySuspended = try {
                    dpm.isPackagesSuspended(admin, arrayOf(pkg)).firstOrNull() == true
                } catch (t: Throwable) {
                    false
                }
                if (reallySuspended) level = Level.SUSPENDED
            }
        } else {
            failures.add("تعلیق برنامه از اندروید ۹ اضافه شده و روی این گوشی کار نمی‌کند.")
        }

        // 2. uninstall block — cheap, always available, and it is what stops the
        //    lock being undone by removing and reinstalling the app.
        val uninstallErr = try {
            dpm.setUninstallBlocked(admin, pkg, true)
            null
        } catch (t: Throwable) {
            "محافظت از حذف $pkg رد شد: ${t.message}"
        }
        if (uninstallErr != null) failures.add(uninstallErr)
        else if (level == Level.NONE) level = Level.UNINSTALL_BLOCKED

        // 3. hide — the launcher icon, the weakest of the three but the one the
        //    user notices first, so it is always worth applying.
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
            // A partial success is worth logging even when the panel reports a
            // lock: the missing mechanism is the difference between a real
            // restriction and a cosmetic one, and it is invisible later.
            failures.forEach { Log.w(TAG, "$pkg partially blocked: $it") }
        }
    }

    /**
     * Undo every mechanism [block] applies.
     *
     * Reversal is not conditional on the call that blocked having succeeded. A
     * phone that was blocked, then had its policy cleared on a different build
     * that could not suspend, would otherwise keep the suspend state forever
     * with no code path that knows to clear it.
     */
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
                dpm.setPackagesSuspended(admin, arrayOf(pkg), false, arrayOf(pkg))
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

    /**
     * Block everything a policy key names, and summarise the result.
     *
     * The summary is deliberately the *weakest* link. A key that hid the gallery
     * but only uninstall-blocked the file browser has not locked anything a user
     * cannot route around, and reporting the strongest success would hide that.
     */
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

    /** Undo everything a policy key named. Used when a key is switched off. */
    fun unblockKey(context: Context, key: String) {
        for (pkg in resolveTargets(context, key)) {
            unblock(context, pkg).reason?.let { Log.w(TAG, "unblock $pkg: $it") }
        }
    }

    /** Whether a package is suspended right now. Read, never assumed. */
    fun isSuspended(context: Context, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin: ComponentName = PrfDeviceAdminReceiver.componentName(context)
        return try {
            // isPackagesSuspended returns one boolean per package asked about, in
            // the same order. Reading index 0 rather than assuming a scalar is
            // what the API actually returns; `firstOrNull` also covers the
            // empty-array case, which cannot happen here but costs nothing.
            dpm.isPackagesSuspended(admin, arrayOf(pkg)).firstOrNull() == true
        } catch (t: Throwable) {
            false
        }
    }
}
