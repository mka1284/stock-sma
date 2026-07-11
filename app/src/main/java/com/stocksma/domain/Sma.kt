package com.stocksma.domain

import kotlin.math.abs

/**
 * Pure SMA / crossing logic. Kept free of Android dependencies so it is unit-testable.
 */
object Sma {

    /**
     * Simple moving average over [window] days.
     *
     * @param closes chronologically ordered daily closes (oldest first). When a seed is
     *   used, this list must contain only the closes recorded since the seed date.
     * @param seed optional user-entered SMA value acting as a stand-in for the missing
     *   older days while fewer than [window] real data points exist (weighted blend).
     *   Once the window is completely filled with real data the seed is ignored.
     */
    fun compute(closes: List<Double>, window: Int, seed: Double? = null): Double? {
        if (window <= 0) return null
        val recent = closes.takeLast(window)
        return when {
            recent.size >= window -> recent.sum() / window
            seed != null && recent.isNotEmpty() ->
                ((window - recent.size) * seed + recent.sum()) / window
            seed != null -> seed
            else -> null
        }
    }

    fun deviationPct(price: Double, sma: Double): Double = (price - sma) / sma * 100.0

    enum class Side { ABOVE, BELOW }

    fun side(price: Double, sma: Double): Side = if (price >= sma) Side.ABOVE else Side.BELOW

    /**
     * Decides whether a crossing notification should fire.
     *
     * Fires only when the deviation from the SMA is at least [thresholdPct] (a threshold
     * of 0 means any crossing) AND the current side differs from [lastNotifiedSide]
     * (de-duplication: one notification per crossing event), OR the optional cooldown
     * has expired since the last notification.
     *
     * @return the side to notify about, or null when no notification is due.
     */
    fun crossingToNotify(
        price: Double,
        sma: Double,
        thresholdPct: Double,
        lastNotifiedSide: Side?,
        lastNotifiedAtMillis: Long?,
        nowMillis: Long,
        cooldownMillis: Long
    ): Side? {
        if (abs(deviationPct(price, sma)) < thresholdPct) return null
        val current = side(price, sma)
        val rearmedByCooldown = lastNotifiedAtMillis != null && cooldownMillis > 0 &&
            nowMillis - lastNotifiedAtMillis >= cooldownMillis
        return if (current != lastNotifiedSide || rearmedByCooldown) current else null
    }
}
