package com.example.pettracker

import android.content.Context

/**
 * FD-4.3: the last food level the feeder sent (over Bluetooth now, from Firebase once the
 * feeder is on Wi-Fi), saved on the phone so Home and the Feeder page can show it later.
 */
class FoodLevelStorage(context: Context) {

    private val prefs = context.getSharedPreferences("food_level", Context.MODE_PRIVATE)

    /** Food left in %, or null if the feeder never sent it. */
    val percent: Int?
        get() = if (prefs.contains(KEY_PERCENT)) prefs.getInt(KEY_PERCENT, 0) else null

    /** When it was measured, in ms (0 = never). */
    val checkedAt: Long
        get() = prefs.getLong(KEY_TIME, 0L)

    /**
     * Saves a new level. Returns true when the owner should get a low food alert:
     * only the first time it goes below [lowPercent], not again until the feeder is refilled.
     */
    fun save(percent: Int, lowPercent: Int, time: Long = System.currentTimeMillis()): Boolean {
        val alreadyWarned = prefs.getBoolean(KEY_WARNED, false)
        val alert = FoodLevel.shouldAlert(percent, lowPercent, alreadyWarned)
        prefs.edit()
            .putInt(KEY_PERCENT, percent)
            .putLong(KEY_TIME, time)
            .putBoolean(KEY_WARNED, FoodLevel.isLow(percent, lowPercent))
            .apply()
        return alert
    }

    private companion object {
        const val KEY_PERCENT = "percent"
        const val KEY_TIME = "checked_at"
        const val KEY_WARNED = "low_alert_sent"
    }
}

/** FD-4.3: the food level rules, kept apart so they can be unit tested. */
object FoodLevel {

    /** Low when below the % picked in Settings (10, 20 or 30). */
    @JvmStatic
    fun isLow(percent: Int?, lowPercent: Int): Boolean = percent != null && percent < lowPercent

    /** One alert when the food goes low. After a refill (back above the limit) it can warn again. */
    @JvmStatic
    fun shouldAlert(percent: Int, lowPercent: Int, alreadyWarned: Boolean): Boolean =
        isLow(percent, lowPercent) && !alreadyWarned

    /** Once the feeder is on Wi-Fi it writes feeder/status/foodPercent. Null if missing or wrong. */
    @JvmStatic
    fun fromStatus(status: Map<String, Any?>?): Int? {
        val value = (status?.get("foodPercent") as? Number)?.toInt() ?: return null
        return if (value in 0..100) value else null
    }

    /**
     * Food left card when the feeder is unplugged or not connected: the old % is not shown
     * as if it were live, only as "last level" with its time.
     */
    @JvmStatic
    fun offlineNote(lastPercent: Int?, lastTime: String?): String =
        if (lastPercent == null || lastTime == null) "Feeder is off or not connected."
        else "Feeder is off or not connected. Last level $lastPercent% at $lastTime."

    /** The message for the alert box and the alert saved in Firebase. */
    @JvmStatic
    fun alertText(percent: Int): String = "Food is low ($percent% left). Refill the feeder."
}
