package com.example.pettracker

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One collar reading from Firebase, whichever way it was written:
 *  - the ESP32-S2 (COM-3.2) writes {accelerationG: {x,y,z}, gyroscopeDegreesPerSecond: {x,y,z}, receivedAt}
 *  - the Bluetooth backup writes flat {ax, ay, az, gx, gy, gz, time}
 * batteryPercent, and the GPS position (COL-6: latitude, longitude), are read too when the collar sends them.
 */
data class CollarReading(
    val timeMillis: Long,
    val ax: Double, val ay: Double, val az: Double,
    val gx: Double, val gy: Double, val gz: Double,
    val battery: Int?,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
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
            var latitude = number(row["latitude"] ?: row["lat"])
            var longitude = number(row["longitude"] ?: row["lon"])
            if (latitude == 0.0 && longitude == 0.0) { latitude = null; longitude = null }

            return CollarReading(time, ax, ay, az, gx, gy, gz, battery, latitude, longitude)
        }

        /** Firebase gives numbers as Long or Double, sometimes as text. */
        private fun number(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
