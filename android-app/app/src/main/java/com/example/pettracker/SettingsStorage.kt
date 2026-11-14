package com.example.pettracker

import android.content.Context

/**
 * UI-8.2: Settings saved on the phone. SettingsActivity also copies them to Firebase,
 * so the feeder can use the meal limit.
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

    /** COL-5: warn when the pet is much less active than its usual week. */
    var lessActiveAlerts: Boolean
        get() = prefs.getBoolean(KEY_LESS_ACTIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_LESS_ACTIVE, value).apply()

    /** COL-6: warn when the pet leaves the safe zone. */
    var safeZoneAlerts: Boolean
        get() = prefs.getBoolean(KEY_SAFE_ZONE_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SAFE_ZONE_ALERTS, value).apply()

    /** COL-6: size of the safe zone around home, in metres. */
    var safeRadius: Int
        get() = prefs.getInt(KEY_SAFE_RADIUS, DEFAULT_SAFE_RADIUS)
        set(value) = prefs.edit().putInt(KEY_SAFE_RADIUS, value).apply()

    /** COL-6: home position (null until the owner taps "Set home here"). */
    val homeLatitude: Double? get() = prefs.getString(KEY_HOME_LAT, null)?.toDoubleOrNull()
    val homeLongitude: Double? get() = prefs.getString(KEY_HOME_LON, null)?.toDoubleOrNull()

    fun setHome(latitude: Double, longitude: Double) {
        prefs.edit().putString(KEY_HOME_LAT, latitude.toString())
            .putString(KEY_HOME_LON, longitude.toString()).apply()
    }

    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "mealLimit" to mealLimit,
            "lowFoodPercent" to lowFoodPercent,
            "lessActiveAlerts" to lessActiveAlerts,
            "safeZoneAlerts" to safeZoneAlerts,
            "safeRadiusMeters" to safeRadius,
            "updatedAt" to System.currentTimeMillis()
        )
        homeLatitude?.let { map["homeLatitude"] = it }
        homeLongitude?.let { map["homeLongitude"] = it }
        return map
    }

    companion object {
        const val MAX_MEAL_LIMIT = 6
        const val DEFAULT_MEAL_LIMIT = 3
        const val DEFAULT_LOW_FOOD = 20
        const val DEFAULT_SAFE_RADIUS = 100
        private const val KEY_MEAL_LIMIT = "meal_limit"
        private const val KEY_LOW_FOOD = "low_food_percent"
        private const val KEY_LESS_ACTIVE = "less_active_alerts"
        private const val KEY_SAFE_ZONE_ALERTS = "safe_zone_alerts"
        private const val KEY_SAFE_RADIUS = "safe_radius"
        private const val KEY_HOME_LAT = "home_lat"
        private const val KEY_HOME_LON = "home_lon"
    }
}
