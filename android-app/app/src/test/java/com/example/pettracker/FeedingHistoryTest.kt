package com.example.pettracker

import com.example.pettracker.FeedingHistory.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FD-6.2: the feeding history list. */
class FeedingHistoryTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun row(time: Long, status: String, portion: Int = 2, source: String = "feed_now") =
        mapOf<String, Any?>("time" to time, "status" to status, "portion" to portion.toLong(), "source" to source)

    @Test
    fun newestFirst() {
        val meals = FeedingHistory.fromRows(listOf(row(now - 60 * min, "fed"), row(now - 5 * min, "fed"), row(now - 30 * min, "fed")), now)
        assertEquals(listOf(now - 5 * min, now - 30 * min, now - 60 * min), meals.map { it.time })
    }

    @Test
    fun marksEachState() {
        val meals = FeedingHistory.fromRows(listOf(
            row(now - 10 * min, "fed"),
            row(now - 9 * min, "failed"),
            row(now - 8 * min, "pending"),        // no reply after 2 minutes
            row(now - 1 * min, "pending"),        // still waiting
            row(now - 7 * min, "scheduled")       // planned meal whose time has passed, never confirmed
        ), now).associate { it.time to it.state }
        assertEquals(State.GIVEN, meals[now - 10 * min])
        assertEquals(State.FAILED, meals[now - 9 * min])
        assertEquals(State.NO_REPLY, meals[now - 8 * min])
        assertEquals(State.WAITING, meals[now - 1 * min])
        assertEquals(State.NO_REPLY, meals[now - 7 * min])
    }

    @Test
    fun hidesFutureMealsAndBadRows() {
        val meals = FeedingHistory.fromRows(listOf(
            row(now + 60 * min, "scheduled"),     // tomorrow's planned meal
            mapOf<String, Any?>("status" to "fed"), // no time
            row(now - 5 * min, "fed")
        ), now)
        assertEquals(1, meals.size)
    }

    @Test
    fun groupsByDay() {
        val today = MealLimit.startOfDay(now)
        val meals = FeedingHistory.fromRows(listOf(
            row(today + 8 * 60 * min, "fed"), row(today + 60 * min, "fed"), row(today - 2 * 60 * min, "fed")
        ), today + 9 * 60 * min)
        val days = FeedingHistory.byDay(meals)
        assertEquals(2, days.size)
        assertEquals(today, days[0].first)
        assertEquals(2, days[0].second.size)
        assertTrue(days[1].first < today)
    }

    @Test
    fun names() {
        assertEquals("Small", FeedingHistory.portionName(1))
        assertEquals("Large", FeedingHistory.portionName(3))
        assertEquals("Feed Now", FeedingHistory.sourceName("feed_now"))
        assertEquals("Scheduled", FeedingHistory.sourceName("scheduled"))
        assertEquals("No reply from the feeder", FeedingHistory.stateName(State.NO_REPLY))
    }
}
