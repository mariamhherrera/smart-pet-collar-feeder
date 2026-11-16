package com.example.pettracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** COM-3.3: the feeder is online only if it wrote its status recently. */
class FeederStatusTest {

    private val now = 1_800_000_000_000L

    @Test
    fun onlineWhenSeenRecently() {
        assertTrue(FeederStatus.isOnline(mapOf("connected" to true, "lastSeen" to now - 20_000L), now))
    }

    @Test
    fun offlineWhenSilentTooLong() {
        // Unplugged: it still says connected, but stopped writing 2 minutes ago
        assertFalse(FeederStatus.isOnline(mapOf("connected" to true, "lastSeen" to now - 120_000L), now))
    }

    @Test
    fun offlineWhenMissingData() {
        assertFalse(FeederStatus.isOnline(null, now))
        assertFalse(FeederStatus.isOnline(mapOf("connected" to true), now))
        assertFalse(FeederStatus.isOnline(mapOf("connected" to false, "lastSeen" to now), now))
    }
}
