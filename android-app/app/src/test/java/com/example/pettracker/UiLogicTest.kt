package com.example.pettracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.example.pettracker.bluetooth.FeederMessage
import com.example.pettracker.bluetooth.FeederReply
import com.example.pettracker.charts.ChartData
import org.junit.Test
import java.util.Calendar

/** Tests for the UI logic: safe zone (COL-6), meal limit (UI-8.3), readings (UI-6.1). */
class UiLogicTest {

    // ---------- COL-6 GPS location and safe zone ----------

    private fun at(lat: Double, lon: Double) =
        CollarReading(1L, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, battery = null, latitude = lat, longitude = lon)

    @Test
    fun distanceBetweenTwoKnownPoints() {
        // 0.001 degree of latitude is about 111 m everywhere on Earth
        val d = SafeZone.distanceMeters(45.4970, -73.5790, 45.4980, -73.5790)
        assertTrue(d in 105.0..117.0)
        assertEquals(0.0, SafeZone.distanceMeters(45.5, -73.6, 45.5, -73.6), 1e-6)
    }

    @Test
    fun safeZoneInsideAndOutside() {
        val homeLat = 45.4970; val homeLon = -73.5790
        assertEquals(SafeZone.Status.INSIDE, SafeZone.status(at(45.4972, -73.5790), homeLat, homeLon, 100))  // ~22 m
        assertEquals(SafeZone.Status.OUTSIDE, SafeZone.status(at(45.4990, -73.5790), homeLat, homeLon, 100)) // ~222 m
    }

    @Test
    fun safeZoneNeedsHomeAndGps() {
        assertEquals(SafeZone.Status.NO_HOME, SafeZone.status(at(45.5, -73.6), null, null, 100))
        val noGps = CollarReading(1L, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, battery = null)
        assertEquals(SafeZone.Status.NO_GPS, SafeZone.status(noGps, 45.5, -73.6, 100))
    }

    @Test
    fun readsGpsFromFirebaseAndIgnoresZeroZero() {
        val row = mapOf("ax" to 0.0, "ay" to 0.0, "az" to 1.0, "latitude" to 45.4970, "longitude" to -73.5790)
        val r = CollarReading.fromMap(row)!!
        assertTrue(r.hasLocation())
        assertEquals(45.4970, r.latitude!!, 1e-9)
        val noFix = mapOf("ax" to 0.0, "ay" to 0.0, "az" to 1.0, "lat" to 0.0, "lon" to 0.0)
        assertFalse(CollarReading.fromMap(noFix)!!.hasLocation())
    }

    @Test
    fun distanceText() {
        assertEquals("12 m", SafeZone.formatDistance(12.4))
        assertEquals("1.4 km", SafeZone.formatDistance(1400.0))
    }

    // ---------- UI-8.3 meal limit ----------

    private fun at(daysAgo: Int, hour: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0)
        return c.timeInMillis
    }

    private val now = at(0, 20) // today 8 PM

    @Test
    fun countsOnlyTodaysGivenMeals() {
        val rows = listOf(
            mapOf("time" to at(0, 8), "status" to "fed"),
            mapOf("time" to at(0, 12), "status" to "pending"),     // Feed Now sent, counts
            mapOf("time" to at(0, 18), "status" to "scheduled"),   // planned, not given
            mapOf("time" to at(0, 13), "status" to "failed"),      // not given
            mapOf("time" to at(1, 12), "status" to "fed")          // yesterday
        )
        assertEquals(2, MealLimit.mealsToday(rows, now))
    }

    @Test
    fun limitStopsFeeding() {
        assertTrue(MealLimit.canFeed(2, 3))
        assertFalse(MealLimit.canFeed(3, 3))
        assertFalse(MealLimit.canFeed(4, 3))
    }

    @Test
    fun mealsPerDayForTheGraph() {
        val rows = listOf(
            mapOf("time" to at(0, 8), "status" to "fed"),
            mapOf("time" to at(0, 12), "status" to "fed"),
            mapOf("time" to at(2, 8), "status" to "fed"),
            mapOf("time" to at(6, 8), "status" to "fed"),
            mapOf("time" to at(7, 8), "status" to "fed"),          // 8 days ago: outside the graph
            mapOf("time" to at(1, 8), "status" to "scheduled")     // not given
        )
        val counts = MealLimit.mealsPerDay(rows, 7, now)
        assertEquals(listOf(1, 0, 0, 0, 1, 0, 2), counts.toList()) // oldest first, today last
    }

    // ---------- UI-6.1 collar readings from Firebase ----------

    @Test
    fun readsWifiCollarFormat() {
        val row = mapOf(
            "accelerationG" to mapOf("x" to 0.01, "y" to 0.02, "z" to 0.99),
            "gyroscopeDegreesPerSecond" to mapOf("x" to 1.2, "y" to -0.3, "z" to 0.1),
            "receivedAt" to 1760000000000L
        )
        val r = CollarReading.fromMap(row)!!
        assertEquals(0.99, r.az, 1e-9)
        assertEquals(-0.3, r.gy, 1e-9)
        assertEquals(1760000000000L, r.timeMillis)
        assertFalse(r.hasLocation())
    }

    @Test
    fun readsBluetoothBackupFormat() {
        val row = mapOf("ax" to 0.0, "ay" to 0L, "az" to "1.0", "time" to 5L, "batteryPercent" to 77)
        val r = CollarReading.fromMap(row)!!
        assertEquals(1.0, r.az, 1e-9)
        assertEquals(77, r.battery)
        assertEquals(0.0, r.movement(), 1e-9) // still: only gravity
    }

    @Test
    fun rowWithoutAccelerationIsIgnored() {
        assertNull(CollarReading.fromMap(mapOf("time" to 5L)))
        assertNull(CollarReading.fromMap(null))
    }

    @Test
    fun historyRowKeepsTheTime() {
        val r = CollarReading(1234L, 0.1, 0.2, 0.9, 0.0, 0.0, 0.0, battery = null, latitude = 45.5, longitude = -73.6)
        val row = r.toHistoryRow()
        assertEquals(1234L, row["time"])
        assertEquals(45.5, row["latitude"])
        assertFalse(row.containsKey("batteryPercent"))
    }

    // ---------- UI-8.3 meal limit sent to the feeder ----------

    @Test
    fun limitCommandAndReply() {
        assertEquals("LIMIT,3", FeederMessage.limitCommand(3))
        assertEquals(FeederReply.LimitSet(3), FeederMessage.parse("OK LIMIT 3"))
        assertEquals(FeederReply.ScheduledSkipped, FeederMessage.parse("SCHEDULED ERR DAILY LIMIT"))
    }

    // ---------- UI-6.2 a whole day in one small graph ----------

    @Test
    fun averageDownKeepsTheShape() {
        val day = FloatArray(17280) { if (it < 8640) 0f else 1f } // still in the morning, moving after
        val points = ChartData.averageDown(day, 288)
        assertEquals(288, points.size)
        assertEquals(0f, points[0])
        assertEquals(1f, points[287])
        val few = floatArrayOf(1f, 2f)
        assertEquals(few.toList(), ChartData.averageDown(few, 288).toList()) // small lists stay as they are
    }
}
