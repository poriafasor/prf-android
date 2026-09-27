package com.prf.security.util

import kotlin.random.Random























object Ticker {

    
    private val PRIZES = listOf(
        "۵ گیگ اینترنت" to 50,
        "۵۰ هزارتومن شارژ" to 30,
        "۱۰۰ هزارتومن شارژ" to 20,
    )

    private val PREFIXES = listOf(
        "0992", "0993", "0994",              
        "0912", "0913", "0914", "0910",      
        "0909",                             
    )

    data class Row(val masked: String, val carrier: String, val prize: String)

    
    fun row(random: Random = Random.Default): Row {
        val prefix = PREFIXES[random.nextInt(PREFIXES.size)]
        val head = (1000..9999).random(random)
        val tail = random.nextInt(10)
        val carrier = carrierFor(prefix)
        var acc = 0
        val r = random.nextInt(100)
        var prize = PRIZES.last().first
        for ((label, weight) in PRIZES) {
            acc += weight
            if (r < acc) { prize = label; break }
        }
        return Row(
            masked = Persian.toPersianDigits(prefix + head) + "******" + Persian.toPersianDigits(tail.toString()),
            carrier = carrier,
            prize = prize,
        )
    }

    
    fun seed(count: Int = 6, random: Random = Random.Default): List<Row> =
        (0 until count).map { row(random) }

    private fun carrierFor(prefix: String): String = when {
        prefix.startsWith("09") && prefix[2] in '2'..'4' -> "ایرانسل"
        prefix.startsWith("09") && prefix[2] == '0' -> "رایتل"
        else -> "همراه اول"
    }
}
