package com.prf.security.util

/**
 * Persian digits and Iranian phone numbers.
 *
 * A Persian keyboard produces ۰۱۲۳۴۵۶۷۸۹, and people paste numbers written with a
 * +98 or a leading zero missing. The attendance field has to accept all of that
 * and hand the server one shape, so the folding happens here, once, and the
 * server's normalizePhone() is the second line of defence rather than the first.
 */
object Persian {

    private val TO_ASCII = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9',
    )

    private val TO_PERSIAN = mapOf(
        '0' to '۰', '1' to '۱', '2' to '۲', '3' to '۳', '4' to '۴',
        '5' to '۵', '6' to '۶', '7' to '۷', '8' to '۸', '9' to '۹',
    )

    /** Folds Persian and Arabic digits down to ASCII, for storage. */
    fun toAsciiDigits(s: String): String =
        s.map { TO_ASCII[it] ?: it }.joinToString("")

    /** Renders ASCII digits as Persian ones, for anything the user reads. */
    fun toPersianDigits(s: String): String =
        s.map { TO_PERSIAN[it] ?: it }.joinToString("")

    /**
     * Normalises an Iranian mobile number to 09XXXXXXXXX, or returns null when
     * the text is not one. Same rule as the server's normalizePhone: a number
     * that cannot be read is reported as missing, never guessed at.
     */
    fun normalizePhone(input: String): String? {
        var s = toAsciiDigits(input).trim().replace(Regex("[^0-9+]"), "")
        s = when {
            s.startsWith("+98") -> "0" + s.substring(3)
            s.startsWith("0098") -> "0" + s.substring(4)
            s.startsWith("98") && s.length == 12 -> "0" + s.substring(2)
            s.length == 10 && s.startsWith("9") -> "0" + s
            else -> s
        }
        return if (s.length == 11 && s.startsWith("09") && s.drop(2).all { it.isDigit() }) s else null
    }

    /** The Iranian carriers, keyed by the MCC/MNC prefix the SIM reports. */
    val OPERATORS: List<Pair<String, String>> = listOf(
        "Hamrah-e Aval" to "01",   // 001-01
        "Irancell" to "02",   // 001-02
        "Rightel" to "03",   // 001-03
        "Shatel" to "05",   // 001-05
        "Ratel" to "08",   // 001-08
        "ApTel" to "14",   // 001-14
    )

    /**
     * Guesses the carrier from the network's MCC/MNC, or null when the SIM is
     * absent, unregistered, or from a network not on the list. The picker opens
     * on this guess but the user can always change it — the guess never becomes
     * the answer by itself.
     */
    fun operatorFromMccMnc(mccMnc: String): String? {
        if (mccMnc.length < 5 || mccMnc.startsWith("310")) return null
        val mnc = mccMnc.substring(3).trimStart('0').ifEmpty { "0" }
        return OPERATORS.firstOrNull { it.second == mnc }?.first
    }
}
