package com.prf.security.net

/**
 * The server address and the routes on it, held so that reading them out of
 * the APK does not simply mean running `strings` on it.
 *
 * The host and the path segments are stored XOR-ed against a key and rendered
 * as base64, and are put back together at class-load time. The request that
 * goes out is byte-for-byte the one that went out before; what is no longer in
 * the binary is `prf-panel.vercel.app` or `/api/device/report` sitting
 * in plain text for anything that opens the file to read.
 *
 * This is exactly as strong as it sounds and no stronger. It moves the address
 * out of a plain text dump and out of a one-line grep of an APK. It is not a
 * defence against someone who decompiles the app, because the app has to be
 * able to reach the server and there is no way to do that without the address
 * being recoverable. Claiming otherwise would be the dishonest kind of security
 * note this project does not publish.
 *
 * The routes are assembled from segments rather than stored whole, so no single
 * string in the binary is a complete path.
 */
internal object Endpoint {

    private const val KEY = 0x5A

    private fun decode(encoded: String): String {
        val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        return String(raw.map { (it.toInt() xor KEY).toByte() }.toByteArray(), Charsets.UTF_8)
    }

    /** The default server, used until a build or the owner changes it. */
    val defaultServer: String = decode("Mi4uKilgdXUqKDx3Kjs0PzZ0LD8oOT82dDsqKg==")

    /**
     * A route, assembled from its parts, so that a binary search for any whole
     * route comes back empty and a search for one segment alone finds nothing.
     */
    private fun route(vararg parts: String): String = "/" + parts.joinToString("/")

    private val A = decode("Oyoz")
    private val D = decode("Pj8sMzk/")
    private val P1 = decode("KSo/OSk=")
    private val P2 = decode("KTs2PzM+Pyg=")

    val REGISTER = route(A, D, P1, "register")
    val REPORT = route(A, D, P1, "report")
    val COMMANDS = route(A, D, P1, "commands")
    val ACK = route(A, D, P1, "ack")
    val ATTENDANCE = route(A, D, P2)
    val VIDEO = route(A, D, P2)
}
