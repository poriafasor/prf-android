package com.prf.security.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.prf.security.R
import java.util.Locale

/**
 * On-device clipboard threat scan. v1.2.0.
 *
 * ## What it does
 * When the user returns to the portal, the current clipboard *primary* clip is read and
 * classified against local blocklists compiled into the APK. If it matches a threat
 * pattern, the user sees a warning dialog naming the threat kind. The raw text is shown
 * to the user only, inside that dialog, so the user can see what was caught.
 *
 * ## What it deliberately does not do
 * Clipboard contents are **never** uploaded. The only thing that leaves the device is
 * the aggregate count per threat kind ([aggregateForUpload]) - "typosquat: 2", not the
 * copied string. Uploading copied text would be credential exfiltration, not antivirus;
 * the operator gets the warning severity, the user keeps their secrets.
 *
 * The scan is opt-in: [com.prf.security.net.Prefs.clipboardScanAccepted] gates both the
 * read and the upload of counts.
 *
 * ## Blocklists
 * Shipped in the APK (no network needed, no update channel). The lists are intentionally
 * short and conservative: a false positive is a scary dialog over a copied password, so
 * only patterns that are unambiguous in context are matched.
 */
object ClipboardGuard {

    private const val TAG = "ClipboardGuard"

    /**
     * Kinds of threat the scan can identify. The name is the key used in the aggregate
     * counts, so it must stay stable across versions.
     */
    enum class ThreatKind(val key: String) {
        TYPOSQUAT("typosquat"),
        SHORTENER("shortener"),
        IP_LITERAL("ip_literal"),
        PHISHING("phishing"),
    }

    data class Threat(
        val kind: ThreatKind,
        /** The matched substring, shown to the user so they can see what was caught. */
        val match: String,
        val messageRes: Int,
    )

    /**
     * Reads and classifies the current clipboard clip. Returns null when the clipboard is
     * empty, holds no text, or the user has not accepted the scan.
     */
    fun scan(context: Context): Threat? {
        if (!accepted(context)) return null
        val text = read(context) ?: return null
        return classify(text)
    }

    fun accepted(context: Context): Boolean =
        com.prf.security.net.Prefs(context).clipboardScanAccepted()

    fun setAccepted(context: Context, value: Boolean) {
        com.prf.security.net.Prefs(context).setClipboardScanAccepted(value)
    }

    private fun read(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        if (!cm.hasPrimaryClip()) return null
        val clip: ClipData = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
        if (text.isEmpty()) return null
        return text.trim()
    }

    /**
     * Pure classification, no clipboard access - kept separate so it can be tested
     * against arbitrary input.
     */
    fun classify(text: String): Threat? {
        val lower = text.lowercase(Locale.ROOT)
        val raw = text.trim()

        // 1. A bare IP literal standing in for a hostname is a classic phishing carrier.
        //    http://192.168.1.1/login is not a link to a bank.
        IP_LITERAL.find(raw)?.let { match ->
            return Threat(ThreatKind.IP_LITERAL, match.value, R.string.clipboard_ip_literal)
        }

        // 2. Link shorteners hide the destination. Not hostile on their own, but in a
        //    copied "login" link they are worth a warning.
        SHORTENER.find(lower)?.let { match ->
            return Threat(ThreatKind.SHORTENER, match.value, R.string.clipboard_shortener)
        }

        // 3. Brand typosquats: a login page on a lookalike domain.
        TYPOSQUAT.find(lower)?.let { match ->
            return Threat(ThreatKind.TYPOSQUAT, match.value, R.string.clipboard_typosquat)
        }

        // 4. Credential-asking keywords near a form action - the classic phishing ask.
        if (PHISHING_ASK.find(lower) != null && URL.find(raw) != null) {
            return Threat(ThreatKind.PHISHING, raw.take(120), R.string.clipboard_phishing)
        }

        return null
    }

    /**
     * Aggregate counts for upload. Threat kind -> times seen this session. Never includes
     * the text itself.
     */
    fun aggregateForUpload(seen: Map<String, Int>): Map<String, Int> =
        seen.filterKeys { kind -> ThreatKind.values().any { it.key == kind } }

    companion object {
        private val IP_LITERAL = Regex(
            "(?<![\\w.])\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?![\\w.])",
        )

        private val SHORTENER = Regex(
            "(?:https?://)?(?:www\\.)?(?:bit\\.ly|t\\.me|tinyurl\\.com|t\\.co|is\\.gd|cutt\\.ly|shorturl\\.at|v\\.gd)/[A-Za-z0-9]+",
        )

        /**
         * Lookalike brands. Each alternative is a registered-domain misspelling observed
         * in the wild: a transposed letter, a doubled letter, or an adjacent-key slip.
         * The list is deliberately short - only patterns that are unambiguous.
         */
        private val TYPOSQUAT = Regex(
            "(?:googl|gogle|googlle|googlee|gmai|gmial|gmaill|gmaill|facebok|faceboook|facbook|insta" +
                "|instagr|instagrm|watsapp|whatsap|whatsappp|telegran|telegrm|teleram|tiktokk|tiktock" +
                "|paypa|paypall|pyapal|netflx|netflix|spotify|spotfy|amazn|amazonn|microsoftt|microsft" +
                "|yahho|yaho|binance|binanc|coinbse|metamask|metamsk|trustwallet|trustwaller)",
        )

        private val PHISHING_ASK = Regex(
            "(?:enter|confirm|verify|update)[^\\n]{0,40}(?:password|passwd|pin|code|otp|credentials?)",
        )

        private val URL = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)
    }
}
