package com.example.pettracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** COL-13.1: one notification when the pet leaves the safe zone, then none until it is back. */
class SafeZoneAlertTest {

    @Test
    fun leavingAlertsOnce() {
        val first = SafeZoneAlert.decide(SafeZone.Status.OUTSIDE, alreadyAlerted = false)
        assertTrue(first.notify)
        val second = SafeZoneAlert.decide(SafeZone.Status.OUTSIDE, first.alerted)
        assertFalse(second.notify) // still outside: no second notification
    }

    @Test
    fun comingBackAllowsTheNextAlert() {
        val home = SafeZoneAlert.decide(SafeZone.Status.INSIDE, alreadyAlerted = true)
        assertFalse(home.notify)
        assertTrue(SafeZoneAlert.decide(SafeZone.Status.OUTSIDE, home.alerted).notify)
    }

    @Test
    fun noGpsOrNoHomeChangesNothing() {
        assertEquals(SafeZoneAlert.Decision(false, true), SafeZoneAlert.decide(SafeZone.Status.NO_GPS, true))
        assertEquals(SafeZoneAlert.Decision(false, false), SafeZoneAlert.decide(SafeZone.Status.NO_HOME, false))
    }

    @Test
    fun realWalkOut() {
        // Home at Concordia, safe zone 100 m. About 50 m away = inside, about 240 m away = outside.
        val homeLat = 45.4973; val homeLon = -73.5790
        fun at(lat: Double, lon: Double) = CollarReading.fromMap(mapOf(
            "accelerationG" to mapOf("x" to 0.0, "y" to 0.0, "z" to 1.0), "receivedAt" to 5L,
            "gps" to mapOf("latitude" to lat, "longitude" to lon)))
        var alerted = false
        val notes = mutableListOf<Boolean>()
        for (spot in listOf(at(45.4977, -73.5790), at(45.4995, -73.5790), at(45.4996, -73.5791), at(45.4974, -73.5790), at(45.4995, -73.5790))) {
            val d = SafeZoneAlert.decide(SafeZone.status(spot, homeLat, homeLon, 100), alerted)
            alerted = d.alerted; notes.add(d.notify)
        }
        // inside, OUT (alert), still out, back inside, OUT again (alert)
        assertEquals(listOf(false, true, false, false, true), notes)
    }
}
