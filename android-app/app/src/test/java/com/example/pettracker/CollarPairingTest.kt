package com.example.pettracker

import com.example.pettracker.HistoryRange.Range
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * COM-4.4: tests for pairing the collar.
 * COL-2: tests for reading the activity and the minutes the collar sends.
 */
class CollarPairingTest {

    private val now = 1_763_150_000_000L
    private val me = "my-uid"

    // ---------- Collar ID ----------

    @Test fun idIsCleanedUp() {
        assertEquals("PC-3C71F2", CollarPairing.normalizeId("PC-3C71F2"))
        assertEquals("PC-3C71F2", CollarPairing.normalizeId("  pc-3c71f2 "))
        assertEquals("PC-3C71F2", CollarPairing.normalizeId("PC 3C71F2"))
        assertEquals("PC-3C71F2", CollarPairing.normalizeId("3c71f2"))
    }

    @Test fun wrongIdsAreRefused() {
        assertNull(CollarPairing.normalizeId(""))
        assertNull(CollarPairing.normalizeId("PC-3C71F"))     // too short
        assertNull(CollarPairing.normalizeId("PC-3C71F2A"))   // too long
        assertNull(CollarPairing.normalizeId("PC-3C71G2"))    // G is not a hex digit
    }

    // ---------- Online and signal ----------

    @Test fun onlineForThirtySeconds() {
        assertTrue(CollarPairing.isOnline(now - 5_000, now))
        assertFalse(CollarPairing.isOnline(now - 30_000, now))
        assertFalse(CollarPairing.isOnline(null, now))
        assertFalse(CollarPairing.isOnline(0, now))
    }

    @Test fun signalWords() {
        assertEquals("Good", CollarPairing.signalName(-55))
        assertEquals("Fair", CollarPairing.signalName(-70))
        assertEquals("Weak", CollarPairing.signalName(-80))
        assertEquals("--", CollarPairing.signalName(null))
    }

    // ---------- Collar found ----------

    private fun seen(id: String, secondsAgo: Int, owner: String? = null) =
        CollarPairing.SeenCollar(id, now - secondsAgo * 1000L, owner)

    @Test fun newestFreeCollarIsFound() {
        val found = CollarPairing.pickFound(
            listOf(seen("PC-000001", 20), seen("PC-000002", 3)), me, emptySet(), now)
        assertEquals("PC-000002", found?.id)
    }

    @Test fun collarsOfOtherAccountsAreNotFound() {
        val found = CollarPairing.pickFound(listOf(seen("PC-000001", 3, "someone-else")), me, emptySet(), now)
        assertNull(found)
    }

    @Test fun myOwnCollarCanBeFoundAgain() {
        val found = CollarPairing.pickFound(listOf(seen("PC-000001", 3, me)), me, emptySet(), now)
        assertEquals("PC-000001", found?.id)
    }

    @Test fun notMyCollarIsSkipped() {
        val collars = listOf(seen("PC-000001", 3), seen("PC-000002", 10))
        assertEquals("PC-000002", CollarPairing.pickFound(collars, me, setOf("PC-000001"), now)?.id)
    }

    @Test fun collarsSilentForAMinuteAreNotFound() {
        assertNull(CollarPairing.pickFound(listOf(seen("PC-000001", 61)), me, emptySet(), now))
    }

    // ---------- Minutes ----------

    @Test fun minutesText() {
        assertEquals("0 min", CollarPairing.formatMinutes(59))
        assertEquals("42 min", CollarPairing.formatMinutes(42 * 60))
        assertEquals("2 h 05 min", CollarPairing.formatMinutes(125 * 60))
    }

    // ---------- What the collar sends (COL-2) ----------

    private val row = mapOf(
        "accelerationG" to mapOf("x" to 0.01, "y" to 0.02, "z" to 0.99),
        "gyroscopeDegreesPerSecond" to mapOf("x" to 1.0, "y" to 0.0, "z" to 0.0),
        "receivedAt" to now,
        "batteryPercent" to 84L,
        "wifiSignal" to -58L,
        "activity" to "walking",
        "stepsPerSecond" to 1.75,
        "today" to mapOf(
            "date" to "2026-11-14",
            "restingSeconds" to 3600L,
            "walkingSeconds" to 1200L,
            "runningSeconds" to 240L
        )
    )

    @Test fun activityIsRead() {
        val reading = CollarReading.fromMap(row)!!
        assertEquals("Walking", reading.activityLabel())
        assertEquals(1.75, reading.stepsPerSecond!!, 0.001)
        assertEquals(84, reading.battery)
        assertEquals(-58, reading.wifiSignal)
    }

    @Test fun todayMinutesAreRead() {
        val reading = CollarReading.fromMap(row)!!
        assertArrayEquals(longArrayOf(3600, 1200, 240), reading.secondsToday("2026-11-14"))
    }

    @Test fun yesterdaysMinutesShowAsZero() {
        val reading = CollarReading.fromMap(row)!!
        assertArrayEquals(longArrayOf(0, 0, 0), reading.secondsToday("2026-11-15"))
    }

    @Test fun firstSecondsHaveNoActivity() {
        val reading = CollarReading.fromMap(row - "activity")!!
        assertNull(reading.activityLabel())
    }

    // ---------- History minutes (COL-2.3) ----------

    private fun noon(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, 12, 0)
        return c.timeInMillis
    }

    private fun day(resting: Long, walking: Long, running: Long) =
        mapOf("restingSeconds" to resting, "walkingSeconds" to walking, "runningSeconds" to running)

    @Test fun weekGraphShowsActiveMinutesPerDay() {
        val today = noon(2026, 11, 14)
        val days = mapOf(
            "2026-11-13" to day(3600, 600, 120),    // 12 active minutes
            "2026-11-14" to day(1800, 1200, 0),     // 20 active minutes
            "2026-10-01" to day(9999, 9999, 9999)   // outside the week
        )
        val result = HistoryRange.activityMinutesPerBar(days, Range.WEEK, today)!!
        assertEquals(7, result.activeMinutes.size)
        assertEquals(12f, result.activeMinutes[5], 0.01f)
        assertEquals(20f, result.activeMinutes[6], 0.01f)
        assertEquals(5400L, result.restingSeconds)
        assertEquals(1800L, result.walkingSeconds)
        assertEquals(120L, result.runningSeconds)
    }

    @Test fun noCollarDataMeansNoGraph() {
        assertNull(HistoryRange.activityMinutesPerBar(emptyMap(), Range.WEEK, noon(2026, 11, 14)))
    }
}
