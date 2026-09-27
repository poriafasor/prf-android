package com.prf.security.util

import kotlin.random.Random






























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

    
    const val COOLDOWN_MS = 24 * 60 * 60 * 1000L

    







    const val UNIT_MS = 3 * 60 * 1000L

    






    const val RE_SPIN_WINDOW_MS = 24 * 60 * 60 * 1000L

    











    fun spin(random: Random = Random.Default): Slice {
        val total = SLICES.sumOf { it.weight }
        var roll = random.nextInt(total)
        for (s in SLICES) {
            roll -= s.weight
            if (roll < 0) return s
        }
        return SLICES.last()
    }

    
    fun centerAngle(index: Int): Float {
        val total = SLICES.sumOf { it.weight }.toFloat()
        var acc = 0f
        for (i in 0 until index) acc += SLICES[i].weight
        val before = acc
        val own = SLICES[index].weight
        
        
        val mid = if (own <= 0) before else before + own / 2f
        return (mid / total) * 360f
    }

    

    






    fun canSpin(lastSpinAt: Long, reSpinUntil: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (lastSpinAt <= 0L) return true
        if (reSpinUntil > now) return true
        return now - lastSpinAt >= COOLDOWN_MS
    }

    





    fun lockReason(lastSpinAt: Long, reSpinUntil: Long, now: Long = System.currentTimeMillis()): String? {
        if (canSpin(lastSpinAt, reSpinUntil, now)) return null
        val until = if (reSpinUntil > now) reSpinUntil - now else (lastSpinAt + COOLDOWN_MS) - now
        val minutes = (until / 60_000L) + 1L
        return Persian.toPersianDigits(minutes.toString()) + " دقیقه دیگر"
    }

    
    fun reSpinAfter(hit: Slice, now: Long = System.currentTimeMillis()): Long =
        if (hit.kind == "again") now + RE_SPIN_WINDOW_MS else 0L
}
