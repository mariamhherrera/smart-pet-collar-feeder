package com.example.pettracker

import com.example.pettracker.bluetooth.FeederMessage
import com.example.pettracker.bluetooth.FeederReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeederMessageTest {

    @Test
    fun parsesEveryReplyTheFirmwareSends() {
        // reply(...) calls in feeder_bluetooth.ino
        assertEquals(FeederReply.Fed(1), FeederMessage.parse("OK FED 1"))
        assertEquals(FeederReply.Fed(6), FeederMessage.parse("OK FED 6"))
        assertEquals(FeederReply.Cooldown, FeederMessage.parse("ERR COOLDOWN"))
        assertEquals(FeederReply.DailyLimit, FeederMessage.parse("ERR DAILY LIMIT"))
        assertEquals(FeederReply.Unknown("HELLO"), FeederMessage.parse("ERR UNKNOWN HELLO"))
    }

    @Test
    fun ignoresSpacesAndLineEndings() {
        assertEquals(FeederReply.Fed(2), FeederMessage.parse("OK FED 2\r"))
        assertEquals(FeederReply.Cooldown, FeederMessage.parse("  ERR COOLDOWN  "))
    }

    @Test
    fun rejectsLinesThatAreNotAFeederReply() {
        val bad = listOf(
            "",
            "OK FED",          // count missing
            "OK FED abc",      // count is not a number
            "OK FED 0",        // the first feed is 1
            "OK FED -2",
            "Bluetooth started! Waiting for messages...",
            "12.340,0.0123,-0.0456,0.9981,1.2210,-0.3050,0.0000" // a collar reading
        )

        for (line in bad) {
            assertNull("Should be rejected: \"$line\"", FeederMessage.parse(line))
        }
    }

    @Test
    fun givesAClearSentenceForEachReply() {
        assertEquals("Food given (1 meal today)", FeederMessage.screenText(FeederReply.Fed(1)))
        assertEquals("Food given (3 meals today)", FeederMessage.screenText(FeederReply.Fed(3)))
        assertEquals(
            "Please wait a few seconds before feeding again",
            FeederMessage.screenText(FeederReply.Cooldown)
        )
        assertEquals(
            "Daily limit reached, no more food today",
            FeederMessage.screenText(FeederReply.DailyLimit)
        )
        assertEquals(
            "The feeder did not understand the command",
            FeederMessage.screenText(FeederReply.Unknown("HELLO"))
        )
    }
}
