package com.example.pettracker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** COL-10.3 / COL-14.2: the sleep numbers the collar adds to collarDays/<collarId>/<date>. */
class SleepNightsTest {

    @Test
    fun readsTheNightFromTheDayEntry() {
        val day = mapOf("restingSeconds" to 60L, "sleepMinutes" to 473L, "wakeUps" to 1L)
        assertEquals(473L, SleepNights.sleepMinutes(day))
        assertEquals(1L, SleepNights.wakeUps(day))
        assertNull(SleepNights.sleepMinutes(mapOf("restingSeconds" to 60L)))  // no sleep sent yet
    }

    @Test
    fun textIsEasyToRead() {
        assertEquals("7 h 53 min", SleepNights.hoursText(473))
        assertEquals("8 h 05 min", SleepNights.hoursText(485))
        assertEquals("45 min", SleepNights.hoursText(45))
        assertEquals("No wake-ups", SleepNights.wakeUpsText(0))
        assertEquals("1 wake-up", SleepNights.wakeUpsText(1))
        assertEquals("3 wake-ups", SleepNights.wakeUpsText(3))
    }

    @Test
    fun sevenNightsForTheGraph() {
        val keys = SleepNights.lastSevenKeys("2026-11-23")
        assertEquals(listOf("2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20",
            "2026-11-21", "2026-11-22", "2026-11-23"), keys)
        val days = mapOf<String, Map<*, *>>(
            "2026-11-22" to mapOf("sleepMinutes" to 420L),
            "2026-11-23" to mapOf("sleepMinutes" to 473L))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f, 7f, 473 / 60f),
            SleepNights.hoursPerNight(days, keys), 0.001f)
        assertEquals(listOf("2026-02-26", "2026-02-27", "2026-02-28", "2026-03-01",
            "2026-03-02", "2026-03-03", "2026-03-04"), SleepNights.lastSevenKeys("2026-03-04"))
    }
}
