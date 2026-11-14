package com.example.pettracker

import com.example.pettracker.HistoryRange.Range
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** UI-6.3: tests for the Week / Month / Year graphs on the History page. */
class HistoryRangeTest {

    /** A time on a given day (phone time), at noon. Months start at 1 here. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, hour, 0)
        return c.timeInMillis
    }

    // "Now" for every test: Saturday 14 November 2026, noon
    private val now = at(2026, 11, 14)

    private fun meal(time: Long, status: String = "done") = mapOf("time" to time, "status" to status)
    private fun day(readings: Int, active: Int) = mapOf("readings" to readings, "activeReadings" to active)

    // ---------- Bars ----------

    @Test fun weekHas7DaysEndingToday() {
        val keys = HistoryRange.barKeys(Range.WEEK, now)
        assertEquals(7, keys.size)
        assertEquals("2026-11-08", keys.first())
        assertEquals("2026-11-14", keys.last())
    }

    @Test fun monthHas30DaysEndingToday() {
        val keys = HistoryRange.barKeys(Range.MONTH, now)
        assertEquals(30, keys.size)
        assertEquals("2026-10-16", keys.first())
        assertEquals("2026-11-14", keys.last())
    }

    @Test fun yearHas12MonthsEndingThisMonth() {
        val keys = HistoryRange.barKeys(Range.YEAR, now)
        assertEquals(12, keys.size)
        assertEquals("2025-12", keys.first())
        assertEquals("2026-11", keys.last())
    }

    @Test fun rangeStartsAtMidnightOfTheFirstBar() {
        assertEquals(at(2026, 11, 8, 0), HistoryRange.rangeStart(Range.WEEK, now))
        assertEquals(at(2026, 10, 16, 0), HistoryRange.rangeStart(Range.MONTH, now))
        assertEquals(at(2025, 12, 1, 0), HistoryRange.rangeStart(Range.YEAR, now))
    }

    @Test fun labelsHaveOnePerBar() {
        val week = HistoryRange.barLabels(Range.WEEK, HistoryRange.barKeys(Range.WEEK, now))
        assertEquals(7, week.size)
        assertEquals("Today", week.last())

        val month = HistoryRange.barLabels(Range.MONTH, HistoryRange.barKeys(Range.MONTH, now))
        assertEquals(30, month.size)
        assertTrue(month.last().isNotEmpty())   // today's bar has a date
        assertEquals("", month[28])             // yesterday's is blank, so dates don't overlap

        assertEquals(12, HistoryRange.barLabels(Range.YEAR, HistoryRange.barKeys(Range.YEAR, now)).size)
    }

    // ---------- Meals ----------

    @Test fun mealsAreCountedOnTheirDay() {
        val feedings = listOf(
            meal(at(2026, 11, 14, 8)), meal(at(2026, 11, 14, 11)),  // 2 today (before noon)
            meal(at(2026, 11, 8, 9))                                   // 1 on the first day of the week
        )
        assertArrayEquals(intArrayOf(1, 0, 0, 0, 0, 0, 2), HistoryRange.mealsPerBar(feedings, Range.WEEK, now))
    }

    @Test fun plannedAndFailedMealsDontCount() {
        val feedings = listOf(
            meal(at(2026, 11, 14, 8)),
            meal(at(2026, 11, 14, 9), status = "scheduled"),
            meal(at(2026, 11, 14, 10), status = "failed")
        )
        assertEquals(1, HistoryRange.mealsPerBar(feedings, Range.WEEK, now).sum())
    }

    @Test fun mealsOutsideTheRangeDontCount() {
        val feedings = listOf(meal(at(2026, 11, 7)), meal(at(2026, 11, 15)))  // day before the week, tomorrow
        assertEquals(0, HistoryRange.mealsPerBar(feedings, Range.WEEK, now).sum())
    }

    @Test fun yearCountsMealsPerMonth() {
        val feedings = listOf(meal(at(2026, 11, 1)), meal(at(2026, 11, 14)), meal(at(2025, 12, 31)))
        val counts = HistoryRange.mealsPerBar(feedings, Range.YEAR, now)
        assertEquals(1, counts.first())  // December 2025
        assertEquals(2, counts.last())   // November 2026
    }

    // ---------- Activity ----------

    @Test fun activityIsPercentOfReadings() {
        val days = mapOf("2026-11-14" to day(readings = 100, active = 25))
        val bars = HistoryRange.activityPerBar(days, Range.WEEK, now)
        assertNotNull(bars)
        assertEquals(25f, bars!!.percents.last(), 0.01f)
        assertEquals(0f, bars.percents.first(), 0.01f)  // no readings that day
        assertEquals(25, bars.overallPercent)
    }

    @Test fun yearAddsUpTheDaysOfEachMonth() {
        val days = mapOf(
            "2026-11-01" to day(readings = 100, active = 10),
            "2026-11-14" to day(readings = 100, active = 50)
        )
        val bars = HistoryRange.activityPerBar(days, Range.YEAR, now)!!
        assertEquals(30f, bars.percents.last(), 0.01f)  // 60 active out of 200
    }

    @Test fun noReadingsMeansNotEnoughData() {
        assertNull(HistoryRange.activityPerBar(emptyMap(), Range.WEEK, now))
        // A reading from before the range doesn't count either
        assertNull(HistoryRange.activityPerBar(mapOf("2026-01-01" to day(100, 50)), Range.MONTH, now))
    }

    @Test fun movingCollarIsActive() {
        val still = CollarReading(1L, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, battery = null)   // only gravity
        val moving = CollarReading(1L, 0.3, 0.2, 1.2, 0.0, 0.0, 0.0, battery = null)
        assertFalse(HistoryRange.isActive(still))
        assertTrue(HistoryRange.isActive(moving))
    }
}
