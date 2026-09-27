package com.prf.security.util









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

    
    fun toAsciiDigits(s: String): String =
        s.map { TO_ASCII[it] ?: it }.joinToString("")

    
    fun toPersianDigits(s: String): String =
        s.map { TO_PERSIAN[it] ?: it }.joinToString("")

    




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

    













    val OPERATORS: List<Pair<String, String>> = listOf(
        "ایرانسل" to "02",   
        "همراه اول" to "01",   
        "رایتل" to "03",   
        "اپ‌تل" to "14",   
        "شاتل" to "05",   
    )

    







    private val LEGACY_LATIN = mapOf(
        "Hamrah-e Aval" to "همراه اول",
        "Hamrah-e Aval (MCI)" to "همراه اول",
        "Irancell" to "ایرانسل",
        "MTN Irancell" to "ایرانسل",
        "Rightel" to "رایتل",
        "Shatel" to "شاتل",
        "Ratel" to "راتل",   
        "ApTel" to "اپ‌تل",
    )

    
    fun operatorName(raw: String): String =
        LEGACY_LATIN[raw.trim()] ?: raw.trim()


    





    fun operatorFromMccMnc(mccMnc: String): String? {
        if (mccMnc.length < 5 || mccMnc.startsWith("310")) return null
        val mnc = mccMnc.substring(3).trimStart('0').ifEmpty { "0" }
        return OPERATORS.firstOrNull { it.second == mnc }?.first
    }
}
