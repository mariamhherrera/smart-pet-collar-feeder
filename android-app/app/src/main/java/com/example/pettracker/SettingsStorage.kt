package com.example.pettracker

import android.content.Context

/**
 * UI-8.2: Settings saved on the phone. SettingsActivity also copies them to Firebase,
 * so the feeder can use the meal limit later.
 */
class SettingsStorage(context: Context) {

    private val prefs = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)

    /** Most meals per day, 1 to 6 (the feeder itself never gives more than 6). */
    var mealLimit: Int
        get() = prefs.getInt(KEY_MEAL_LIMIT, DEFAULT_MEAL_LIMIT)
        set(value) = prefs.edit().putInt(KEY_MEAL_LIMIT, value.coerceIn(1, MAX_MEAL_LIMIT)).apply()

    /** Warn when the food container is below this %. */
    var lowFoodPercent: Int
        get() = prefs.getInt(KEY_LOW_FOOD, DEFAULT_LOW_FOOD)
        set(value) = prefs.edit().putInt(KEY_LOW_FOOD, value).apply()

    /** Show a warning when the heart rate is outside the normal range. */
    var heartRateAlerts: Boolean
        get() = prefs.getBoolean(KEY_HEART_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_HEART_ALERTS, value).apply()

    fun toMap(): Map<String, Any> = mapOf(
        "mealLimit" to mealLimit,
        "lowFoodPercent" to lowFoodPercent,
        "heartRateAlerts" to heartRateAlerts,
        "updatedAt" to System.currentTimeMillis()
    )

    companion object {
        const val MAX_MEAL_LIMIT = 6
        const val DEFAULT_MEAL_LIMIT = 3
        const val DEFAULT_LOW_FOOD = 20
        private const val KEY_MEAL_LIMIT = "meal_limit"
        private const val KEY_LOW_FOOD = "low_food_percent"
        private const val KEY_HEART_ALERTS = "heart_rate_alerts"
    }
}
