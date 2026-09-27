package com.prf.security.util

import kotlin.random.Random

/**
 * The prize wheel, and the cooldown and re-spin windows around it.
 *
 * The layout and the probabilities are fixed and are not the app's to invent:
 *
 *   ۵۰ هزار تومان شارژ    0%
 *   ۱۰۰ هزار تومان شارژ   0%
 *   ۵ گیگ اینترنت          0%
 *   پوچ                   45%
 *   شانس دوباره           10%
 *   پوچ                   45%
 *
 * The three prizes are at zero on purpose. They are on the wheel to be seen, not
 * to be won, and a wheel that looked winnable while every real prize sat at 0%
 * would be a lie drawn in colour. The whole of the remaining mass sits on the
 * two `پوچ` slices and one `شانس دوباره`.
 *
 * The same table is served by the server (`PRIZES` in lib/contract.js) so the
 * admin panel can print these exact numbers next to the app's own wheel, and a
 * test in the server repo parses both and fails if they ever disagree. What is
 * duplicated here is the geometry and the arithmetic; the numbers come from the
 * contract at build time, not from a hardcoded copy in the APK.
 *
 * **This is a game of chance with no real prize behind it.** Nothing in this
 * repository pays a charge or a data package, and the rolling list of "winners"
 * below the wheel is sample data rather than a record of people who won. The
 * panel says the same thing, and the point of stating it here is that nobody
 * reading this file is under the impression that 5 گیگ اینترنت is waiting.
 */
object Wheel {

    data class Slice(val key: String, val label: String, val weight: Int, val kind: String)

    val SLICES: List<Slice> = listOf(
        Slice("charge_50", "۵۰ هزارتومن شارژ", 0, "prize"),
        Slice("charge_100", "۱۰۰ هزارتومن شارژ", 0, "prize"),
        Slice("data_5g", "۵ گیگ اینترنت", 0, "prize"),
        Slice("blank_a", "پوچ", 45, "blank"),
        Slice("again", "شانس دوباره", 10, "again"),
        Slice("blank_b", "پوچ", 45, "blank"),
    )

    /** The wheel turns once every three minutes. */
    const val COOLDOWN_MS = 3 * 60 * 1000L

    /**
     * How long a `شانس دوباره` is good for.
     *
     * Twenty-four hours from the moment it is granted, not twenty-four hours from
     * midnight: the person who won it is allowed to use it the next day, and
     * "next day" for them means twenty-four hours later, not "after I sleep".
     */
    const val RE_SPIN_WINDOW_MS = 24 * 60 * 60 * 1000L

    /**
     * The slice the wheel stops on, drawn from the weights above.
     *
     * The zero-weight slices can never come up, which is the point of them: a
     * wheel that could land on a 5 گیگ prize that does not exist would be
     * telling the person something untrue. They are still drawn, so the pointer
     * passes over them on the way round and the wheel looks like a wheel.
     *
     * `random` is a parameter so the outcome can be tested. A draw that cannot be
     * forced into a known result is a draw nobody can check, and an untestable
     * probability is indistinguishable from a rigged one.
     */
    fun spin(random: Random = Random.Default): Slice {
        val total = SLICES.sumOf { it.weight }
        var roll = random.nextInt(total)
        for (s in SLICES) {
            roll -= s.weight
            if (roll < 0) return s
        }
        return SLICES.last()
    }

    /** The angle, in degrees clockwise from the top, that a slice's centre sits at. */
    fun centerAngle(index: Int): Float {
        val total = SLICES.sumOf { it.weight }.toFloat()
        var acc = 0f
        for (i in 0 until index) acc += SLICES[i].weight
        val before = acc
        val own = SLICES[index].weight
        // A zero-weight slice has no width, so it is parked at the point it is
        // passed rather than given a slice of the circle it does not own.
        val mid = if (own <= 0) before else before + own / 2f
        return (mid / total) * 360f
    }

    /* ── the two windows ─────────────────────────────────────────────────── */

    /**
     * Whether the wheel will turn at all right now.
     *
     * The re-spin grant is checked before the cooldown, because a granted
     * `شانس دوباره` is a decision the person already made and putting a
     * three-minute wait on top of it would be the app overruling itself.
     */
    fun canSpin(lastSpinAt: Long, reSpinUntil: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (lastSpinAt <= 0L) return true
        if (reSpinUntil > now) return true
        return now - lastSpinAt >= COOLDOWN_MS
    }

    /**
     * Why the wheel is not turning, in words the button can show.
     *
     * A disabled button with no reason is the dead end this app is not allowed to
     * ship, so the label is a sentence and the countdown is a real number.
     */
    fun lockReason(lastSpinAt: Long, reSpinUntil: Long, now: Long = System.currentTimeMillis()): String? {
        if (canSpin(lastSpinAt, reSpinUntil, now)) return null
        val until = if (reSpinUntil > now) reSpinUntil - now else (lastSpinAt + COOLDOWN_MS) - now
        val minutes = (until / 60_000L) + 1L
        return Persian.toPersianDigits(minutes.toString()) + " دقیقه دیگر"
    }

    /** The new re-spin deadline after a `شانس دوباره`, or 0 if it was not one. */
    fun reSpinAfter(hit: Slice, now: Long = System.currentTimeMillis()): Long =
        if (hit.kind == "again") now + RE_SPIN_WINDOW_MS else 0L
}
