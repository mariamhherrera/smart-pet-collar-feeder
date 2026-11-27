package com.example.pettracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FD-7.1: which meals get a "meal failed" notification. */
class MealAlertsTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun row(status: String, result: String?, updatedAt: Long = now - 1 * min, source: String = "feed_now") =
        mapOf<String, Any?>("time" to now - 2 * min, "status" to status, "result" to result,
            "updatedAt" to updatedAt, "portion" to 2L, "source" to source)

    @Test
    fun failedMealWithNoFoodGetsOneAlert() {
        val alerts = MealAlerts.newAlerts(listOf("m1" to row("failed", "ERR NO FOOD")), emptySet(), now)
        assertEquals(1, alerts.size)
        assertEquals("m1", alerts[0].id)
        assertTrue(alerts[0].text.contains("hopper may be empty"))
    }

    @Test
    fun mealThatWorkedGetsNoAlert() {
        assertTrue(MealAlerts.newAlerts(listOf("m1" to row("fed", "OK FED 2")), emptySet(), now).isEmpty())
    }

    @Test
    fun alreadyShownMealIsNotAnnouncedAgain() {
        assertTrue(MealAlerts.newAlerts(listOf("m1" to row("failed", "ERR NO FOOD")), setOf("m1"), now).isEmpty())
    }

    @Test
    fun safetyRuleSkipsGetNoAlert() {
        val rows = listOf("a" to row("failed", "ERR DAILY LIMIT"), "b" to row("failed", "ERR COOLDOWN"))
        assertTrue(MealAlerts.newAlerts(rows, emptySet(), now).isEmpty())
    }

    @Test
    fun oldFailuresAreNotAnnounced() {
        val old = row("failed", "ERR NO FOOD", updatedAt = now - MealAlerts.RECENT_MS - min)
        assertTrue(MealAlerts.newAlerts(listOf("m1" to old), emptySet(), now).isEmpty())
    }

    @Test
    fun offlineAndScheduledMealsSayWhatHappened() {
        val alert = MealAlerts.newAlerts(listOf("m1" to row("failed", "ERR OFFLINE", source = "scheduled")), emptySet(), now).single()
        assertTrue(alert.text.contains("scheduled"))
        assertTrue(alert.text.contains("offline"))
    }

    @Test
    fun usesMealTimeWhenThereIsNoUpdatedAt() {
        val noUpdate = mapOf<String, Any?>("time" to now - 3 * min, "status" to "failed", "result" to "ERR NO FOOD")
        assertEquals(1, MealAlerts.newAlerts(listOf("m1" to noUpdate), emptySet(), now).size)
    }

    @Test
    fun reasonsAndHistoryText() {
        assertNull(MealAlerts.reason("ERR DAILY LIMIT"))
        assertEquals("Daily meal limit reached", FeedingHistory.failReason("ERR DAILY LIMIT"))
        assertTrue(FeedingHistory.failReason("ERR NO FOOD")!!.startsWith("No food came out"))
        assertNull(FeedingHistory.failReason(null))
    }

    @Test
    fun rememberedIdsStaySmall() {
        val ids = (1..80).map { "m$it" }
        assertEquals(50, MealAlerts.trim(ids).size)
        assertEquals("m80", MealAlerts.trim(ids).last())
    }
}
