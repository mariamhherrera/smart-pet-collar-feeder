package com.example.pettracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-5.4: tests for the Collar page rules. */
class CollarVitalsTest {

    // ---------- Normal ranges (UI-5.1) ----------

    @Test fun dogRangeIs60To120() {
        assertEquals(CollarVitals.Range(60, 120), CollarVitals.normalRange("Dog"))
    }

    @Test fun catRangeIs120To180() {
        assertEquals(CollarVitals.Range(120, 180), CollarVitals.normalRange("Cat"))
    }

    @Test fun noRangeWithoutProfile() {
        assertNull(CollarVitals.normalRange(null))
        assertNull(CollarVitals.normalRange("Select species"))
    }

    // ---------- Green or red (UI-5.3) ----------

    @Test fun insideRangeIsNormal() {
        val dog = CollarVitals.Range(60, 120)
        assertTrue(CollarVitals.isNormal(90, dog))
    }

    @Test fun edgesOfRangeAreNormal() {
        val dog = CollarVitals.Range(60, 120)
        assertTrue(CollarVitals.isNormal(60, dog))
        assertTrue(CollarVitals.isNormal(120, dog))
    }

    @Test fun outsideRangeIsNotNormal() {
        val cat = CollarVitals.Range(120, 180)
        assertFalse(CollarVitals.isNormal(119, cat))
        assertFalse(CollarVitals.isNormal(181, cat))
    }

    // ---------- Connected or not ----------

    @Test fun newReadingMeansConnected() {
        assertTrue(CollarVitals.isConnected(lastReadingTime = 100_000, now = 105_000))
    }

    @Test fun oldReadingMeansDisconnected() {
        assertFalse(CollarVitals.isConnected(lastReadingTime = 100_000, now = 130_000))
    }

    @Test fun noReadingMeansDisconnected() {
        assertFalse(CollarVitals.isConnected(lastReadingTime = null, now = 100_000))
    }

    // ---------- Rough activity ----------

    @Test fun smallTurningIsStill() {
        assertEquals("Still", CollarVitals.roughActivity(1.2, -0.3, 0.1))
    }

    @Test fun bigTurningIsMoving() {
        assertEquals("Moving", CollarVitals.roughActivity(40.0, 10.0, 5.0))
    }
}
