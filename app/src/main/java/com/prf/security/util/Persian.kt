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

    /**
     * The Iranian carriers, keyed by the MCC/MNC prefix the SIM reports.
     *
     * The name is the Persian one, because this is the string the person reads
     * in the picker and the string that travels to the server with the record.
     * v2.0.0 showed "Irancell" in a list of Persian labels on an otherwise
     * Persian screen, and a panel full of those rows was the reason the operator
     * column looked like it had come from somewhere else.
     *
     * The `mnc` is what [operatorFromMccMnc] matches on, and the order and the
     * suffixes are checked against the server's own `OPERATORS` by a test — a
     * carrier list that differs on the two sides is a record filed under the
     * wrong network.
     */
    val OPERATORS: List<Pair<String, String>> = listOf(
        "ایرانسل" to "02",   // 001-02
        "همراه اول" to "01",   // 001-01
        "رایتل" to "03",   // 001-03
        "اپ‌تل" to "14",   // 001-14
        "شاتل" to "05",   // 001-05
    )

    /**
     * What the panel is expected to display for a carrier name.
     *
     * Records written by an older build carry the English name, and they are not
     * rewritten: the ledger is a record of what the phone actually sent. The
     * server maps them back for display, and this list is the app's own copy of
     * the same mapping, used when a record has to be shown on the phone.
     */
    private val LEGACY_LATIN = mapOf(
        "Hamrah-e Aval" to "همراه اول",
        "Hamrah-e Aval (MCI)" to "همراه اول",
        "Irancell" to "ایرانسل",
        "MTN Irancell" to "ایرانسل",
        "Rightel" to "رایتل",
        "Shatel" to "شاتل",
        "Ratel" to "راتل",   // no longer offered in the picker; old rows still name it
        "ApTel" to "اپ‌تل",
    )

    /** The Persian name for whatever the phone or the server sent. */
    fun operatorName(raw: String): String =
        LEGACY_LATIN[raw.trim()] ?: raw.trim()


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
