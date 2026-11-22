package com.example.pettracker

import com.example.pettracker.bluetooth.FeederMessage
import com.example.pettracker.bluetooth.FeederReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FD-4.3: food level replies from the feeder and the low food alert. */
class FoodLevelTest {

    @Test
    fun parsesTheFoodLevelReplies() {
        assertEquals(FeederReply.FoodLevel(63), FeederMessage.parse("OK LEVEL 63"))
        assertEquals(FeederReply.FoodLevel(0), FeederMessage.parse("OK LEVEL 0"))
        assertEquals(FeederReply.FoodLevel(100), FeederMessage.parse("OK LEVEL 100\r"))
        assertEquals(FeederReply.NoFoodLevel, FeederMessage.parse("ERR NO LEVEL"))
    }

    @Test
    fun rejectsWrongFoodLevels() {
        assertNull(FeederMessage.parse("OK LEVEL"))
        assertNull(FeederMessage.parse("OK LEVEL abc"))
        assertNull(FeederMessage.parse("OK LEVEL 101"))
        assertNull(FeederMessage.parse("OK LEVEL -5"))
    }

    @Test
    fun lowIsBelowTheSettingsPercent() {
        assertTrue(FoodLevel.isLow(19, 20))
        assertFalse(FoodLevel.isLow(20, 20))
        assertFalse(FoodLevel.isLow(63, 20))
        assertFalse(FoodLevel.isLow(null, 20)) // never checked = no alert
        assertTrue(FoodLevel.isLow(25, 30))
    }

    @Test
    fun warnsOnlyOnceUntilRefilled() {
        assertTrue(FoodLevel.shouldAlert(15, 20, alreadyWarned = false))  // just went low
        assertFalse(FoodLevel.shouldAlert(12, 20, alreadyWarned = true))  // still low, already warned
        assertFalse(FoodLevel.shouldAlert(80, 20, alreadyWarned = true))  // refilled
        assertFalse(FoodLevel.shouldAlert(80, 20, alreadyWarned = false))
    }

    @Test
    fun readsTheLevelTheFeederWritesToFirebase() {
        assertEquals(63, FoodLevel.fromStatus(mapOf("connected" to true, "foodPercent" to 63L)))
        assertNull(FoodLevel.fromStatus(mapOf("connected" to true)))
        assertNull(FoodLevel.fromStatus(mapOf("foodPercent" to 150L)))
        assertNull(FoodLevel.fromStatus(null))
    }

    @Test
    fun offlineFeederShowsLastLevelNotLive() {
        assertEquals("Feeder is off or not connected.", FoodLevel.offlineNote(null, null))
        assertEquals("Feeder is off or not connected. Last level 100% at 10:32.", FoodLevel.offlineNote(100, "10:32"))
    }
}
