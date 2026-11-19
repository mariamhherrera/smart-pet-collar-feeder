package com.example.pettracker

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One collar reading from Firebase, whichever way it was written:
 *  - the ESP32-S2 (COM-3.2) writes {accelerationG: {x,y,z}, gyroscopeDegreesPerSecond: {x,y,z}, receivedAt}
 *  - the Bluetooth backup writes flat {ax, ay, az, gx, gy, gz, time}
 * batteryPercent, and the GPS position (COL-6: latitude, longitude, or COL-9.4: gps.latitude, gps.longitude),
 * are read too when the collar sends them.
 * COL-2: the collar also sends "activity" (resting / walking / running), "stepsPerSecond",
 * and "today" = { date, restingSeconds, walkingSeconds, runningSeconds }.
 */
data class CollarReading(
    val timeMillis: Long,
    val ax: Double, val ay: Double, val az: Double,
    val gx: Double, val gy: Double, val gz: Double,
    val battery: Int?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val activity: String? = null,
    val stepsPerSecond: Double? = null,
    val todayDate: String? = null,
    val restingSeconds: Long = 0,
    val walkingSeconds: Long = 0,
    val runningSeconds: Long = 0,
    val wifiSignal: Int? = null
) {
    /** COL-2.2: "Resting", "Walking" or "Running". Null for the first 4 seconds after the collar starts. */
    fun activityLabel(): String? = when (activity) {
        "resting" -> "Resting"
        "walking" -> "Walking"
        "running" -> "Running"
        else -> null
    }

    /**
     * COL-2.3: today's seconds of resting, walking and running.
     * All 0 if the collar's numbers are from another day (it has not sent since midnight).
     * [todayKey] is today's date like "2026-11-14" (HistoryRange.dayKey).
     */
    fun secondsToday(todayKey: String): LongArray =
        if (todayDate.isNullOrEmpty() || todayDate == todayKey)
            longArrayOf(restingSeconds, walkingSeconds, runningSeconds)
        else longArrayOf(0, 0, 0)

    /** True when the collar sent a GPS position (it has none indoors or before the first fix). */
    fun hasLocation(): Boolean = latitude != null && longitude != null
    /** How much the collar is moving: 0 when still (only gravity, 1 g), bigger when moving. */
    fun movement(): Double = abs(sqrt(ax * ax + ay * ay + az * az) - 1.0)

    /** What gets saved in the readings history (UI-6.1): the values plus the time. */
    fun toHistoryRow(): Map<String, Any> {
        val row = mutableMapOf<String, Any>(
            "time" to timeMillis,
            "ax" to ax, "ay" to ay, "az" to az,
            "gx" to gx, "gy" to gy, "gz" to gz
        )
        battery?.let { row["batteryPercent"] = it }
        latitude?.let { row["latitude"] = it }
        longitude?.let { row["longitude"] = it }
        activity?.let { row["activity"] = it }
        return row
    }

    companion object {
        /** Reads a Firebase row. Returns null if it has no acceleration values. */
        @JvmStatic
        fun fromMap(row: Map<*, *>?): CollarReading? {
            if (row == null) return null
            val accel = row["accelerationG"] as? Map<*, *>
            val gyro = row["gyroscopeDegreesPerSecond"] as? Map<*, *>

            val ax = number(accel?.get("x") ?: row["ax"]) ?: return null
            val ay = number(accel?.get("y") ?: row["ay"]) ?: return null
            val az = number(accel?.get("z") ?: row["az"]) ?: return null
            val gx = number(gyro?.get("x") ?: row["gx"]) ?: 0.0
            val gy = number(gyro?.get("y") ?: row["gy"]) ?: 0.0
            val gz = number(gyro?.get("z") ?: row["gz"]) ?: 0.0

            val time = number(row["receivedAt"] ?: row["time"] ?: row["timestamp"])?.toLong() ?: 0L
            // Field names used by the collar firmware (batteryPercent), or the short ones
            val battery = number(row["batteryPercent"] ?: row["battery"])?.toInt()
            // COL-6 GPS: "latitude"/"longitude" (or "lat"/"lon"). 0,0 means no fix yet, so it is ignored.
            // COL-9.4: the collar firmware sends them inside "gps", and only when the GPS has a fresh fix.
            val gps = row["gps"] as? Map<*, *>
            var latitude = number(row["latitude"] ?: row["lat"] ?: gps?.get("latitude"))
            var longitude = number(row["longitude"] ?: row["lon"] ?: gps?.get("longitude"))
            if (latitude == 0.0 && longitude == 0.0) { latitude = null; longitude = null }

            // COL-2: activity and today's totals (only the new collar firmware sends them)
            val today = row["today"] as? Map<*, *>
            return CollarReading(
                time, ax, ay, az, gx, gy, gz, battery, latitude, longitude,
                activity = row["activity"] as? String,
                stepsPerSecond = number(row["stepsPerSecond"]),
                todayDate = today?.get("date") as? String,
                restingSeconds = number(today?.get("restingSeconds"))?.toLong() ?: 0L,
                walkingSeconds = number(today?.get("walkingSeconds"))?.toLong() ?: 0L,
                runningSeconds = number(today?.get("runningSeconds"))?.toLong() ?: 0L,
                wifiSignal = number(row["wifiSignal"])?.toInt()
            )
        }

        /** Firebase gives numbers as Long or Double, sometimes as text. */
        @JvmStatic
        fun number(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
