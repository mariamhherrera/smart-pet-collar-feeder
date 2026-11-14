package com.example.pettracker

import kotlin.math.sqrt

/**
 * UI-5: the small rules behind the Collar page.
 * No Android code here, so they can be tested with plain unit tests.
 */
object CollarVitals {

    /** A normal heart rate range, in beats per minute. */
    data class Range(val low: Int, val high: Int)

    /**
     * UI-5.1: default normal resting heart rate for each species.
     * Dog: 60-120 bpm. Merck Vet Manual says 70-120, Animal Medical Center says
     *      60-90 for large dogs and 90-120 for small dogs.
     * Cat: 120-180 bpm. Merck Vet Manual says 120-140, Animal Medical Center says 140-180.
     * Later, the Settings page (UI-8) can let the owner change these.
     */
    fun normalRange(species: String?): Range? {
        return when (species) {
            "Dog" -> Range(60, 120)
            "Cat" -> Range(120, 180)
            else -> null // no profile yet, so we don't know the normal range
        }
    }

    /** UI-5.3: true if the heart rate is inside the range (the ends count as normal). */
    fun isNormal(bpm: Int, range: Range): Boolean {
        return bpm >= range.low && bpm <= range.high
    }

    /**
     * The collar uploads a reading every 5 seconds.
     * If we got nothing for 30 seconds (6 missed uploads), we say it's disconnected.
     */
    const val DISCONNECTED_AFTER_MS = 30_000L

    fun isConnected(lastReadingTime: Long?, now: Long): Boolean {
        if (lastReadingTime == null) return false
        return now - lastReadingTime < DISCONNECTED_AFTER_MS
    }

    /**
     * Rough activity from one gyroscope reading (degrees per second).
     * Only "Still" or "Moving" for now. Once COL-2 (step counting) saves
     * resting / walking / running in Firebase, the page shows that instead.
     */
    const val MOVING_ABOVE_DEG_PER_S = 30.0

    fun roughActivity(gx: Double, gy: Double, gz: Double): String {
        val turning = sqrt(gx * gx + gy * gy + gz * gz)
        return if (turning > MOVING_ABOVE_DEG_PER_S) "Moving" else "Still"
    }
}
