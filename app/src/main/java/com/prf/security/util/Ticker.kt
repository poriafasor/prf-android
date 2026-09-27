package com.prf.security.util

import kotlin.random.Random

/**
 * The rolling list of numbers under the wheel.
 *
 * Every ten seconds the bottom row is dropped and a new one appears at the top,
 * with an Iranian mobile number and a prize beside it. The numbers are masked —
 * `09` then four digits, six stars, then the last digit — because a list of
 * eleven-digit numbers scrolling past on someone's lock screen is a list of other
 * people's phone numbers, and this one is on a phone that belongs to whoever is
 * looking at it.
 *
 * **These rows are sample data.** No prize has been paid by this project, and
 * there is no record of anyone having won. The list exists because the request
 * was for one, and it is labelled as what it is on the screen itself — see
 * `wheel_ticker_demo` — and in the panel. Making it look like a record of real
 * customers to people who are trying to win something real would be the exact
 * deception this project is not built for.
 *
 * The prefix is drawn from the real Iranian mobile ranges, because that is what
 * makes the row legible as an Iranian number at all: `0992`/`0993` are Irancell,
 * `0912`/`0913` are MCI, `0909` is Ritel. A random four-digit block with no
 * carrier behind it would be a number nobody can recognise.
 */
object Ticker {

    /** Prizes, with the mass the request asked for: mostly data, then the two charges. */
    private val PRIZES = listOf(
        "۵ گیگ اینترنت" to 50,
        "۵۰ هزارتومن شارژ" to 30,
        "۱۰۰ هزارتومن شارژ" to 20,
    )

    private val PREFIXES = listOf(
        "0992", "0993", "0994",              // ایرانسل
        "0912", "0913", "0914", "0910",      // همراه اول
        "0909",                             // رایتل
    )

    data class Row(val masked: String, val carrier: String, val prize: String)

    /** One row. `random` is a parameter so the masking can be tested. */
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

    /** The starting list, newest at the top. */
    fun seed(count: Int = 6, random: Random = Random.Default): List<Row> =
        (0 until count).map { row(random) }

    private fun carrierFor(prefix: String): String = when {
        prefix.startsWith("09") && prefix[2] in '2'..'4' -> "ایرانسل"
        prefix.startsWith("09") && prefix[2] == '0' -> "رایتل"
        else -> "همراه اول"
    }
}
