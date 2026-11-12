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

/** Tests for the UI logic: heart rate ranges (UI-5), meal limit (UI-8.3), readings (UI-6.1). */
class UiLogicTest {

    // ---------- UI-5.1 / UI-5.3 heart rate ranges ----------

    @Test
    fun dogHeartRateLevels() {
        assertEquals(HeartRateRange.Level.LOW, HeartRateRange.levelOf(59, "Dog"))
        assertEquals(HeartRateRange.Level.NORMAL, HeartRateRange.levelOf(60, "Dog"))
        assertEquals(HeartRateRange.Level.NORMAL, HeartRateRange.levelOf(120, "Dog"))
        assertEquals(HeartRateRange.Level.HIGH, HeartRateRange.levelOf(121, "Dog"))
    }

    @Test
    fun catHeartRateLevels() {
        assertEquals(HeartRateRange.Level.LOW, HeartRateRange.levelOf(119, "Cat"))
        assertEquals(HeartRateRange.Level.NORMAL, HeartRateRange.levelOf(130, "Cat"))
        assertEquals(HeartRateRange.Level.NORMAL, HeartRateRange.levelOf(180, "Cat"))
        assertEquals(HeartRateRange.Level.HIGH, HeartRateRange.levelOf(181, "Cat"))
    }

    @Test
    fun noSpeciesUsesDogRange() {
        assertEquals(60..120, HeartRateRange.rangeFor(null))
        assertEquals("Normal for a dog: 60 to 120 BPM", HeartRateRange.rangeText(null))
    }

    @Test
    fun outsideTheRangeIsRed() {
        val red = 0xFFDC2626.toInt()
        assertEquals(red, HeartRateRange.levelOf(40, "Dog").color)
        assertEquals(red, HeartRateRange.levelOf(200, "Cat").color)
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
        assertNull(r.heartRate)
    }

    @Test
    fun readsBluetoothBackupFormat() {
        val row = mapOf("ax" to 0.0, "ay" to 0L, "az" to "1.0", "time" to 5L, "heartRate" to 90L)
        val r = CollarReading.fromMap(row)!!
        assertEquals(1.0, r.az, 1e-9)
        assertEquals(90, r.heartRate)
        assertEquals(0.0, r.movement(), 1e-9) // still: only gravity
    }

    @Test
    fun rowWithoutAccelerationIsIgnored() {
        assertNull(CollarReading.fromMap(mapOf("time" to 5L)))
        assertNull(CollarReading.fromMap(null))
    }

    @Test
    fun historyRowKeepsTheTime() {
        val r = CollarReading(1234L, 0.1, 0.2, 0.9, 0.0, 0.0, 0.0, heartRate = 80, battery = null)
        val row = r.toHistoryRow()
        assertEquals(1234L, row["time"])
        assertEquals(80, row["heartRate"])
        assertFalse(row.containsKey("battery"))
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
