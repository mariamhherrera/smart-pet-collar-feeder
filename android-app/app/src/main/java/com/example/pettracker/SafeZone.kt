package com.example.pettracker

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * COL-6: the safe zone around home. The owner saves home (the collar's position while the pet
 * is at home) and a radius in Settings. Outside that circle = the pet may have left home.
 */
object SafeZone {

    enum class Status { NO_HOME, NO_GPS, INSIDE, OUTSIDE }

    /** Distance in metres between two GPS points (haversine formula, Earth radius 6,371 km). */
    @JvmStatic
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(sqrt(a))
    }

    @JvmStatic
    fun status(reading: CollarReading?, homeLat: Double?, homeLon: Double?, radiusMeters: Int): Status {
        if (homeLat == null || homeLon == null) return Status.NO_HOME
        if (reading == null || !reading.hasLocation()) return Status.NO_GPS
        val d = distanceMeters(homeLat, homeLon, reading.latitude!!, reading.longitude!!)
        return if (d <= radiusMeters) Status.INSIDE else Status.OUTSIDE
    }

    /** "12 m" or "1.4 km". */
    @JvmStatic
    fun formatDistance(meters: Double): String =
        if (meters < 1000) "${Math.round(meters)} m" else "%.1f km".format(meters / 1000)
}
