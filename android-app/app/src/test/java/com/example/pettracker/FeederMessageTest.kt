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
        assertEquals(FeederReply.ScheduleSaved(7, 30, 2), FeederMessage.parse("OK SCHEDULE 07:30 2"))
        assertEquals(FeederReply.TimeSet, FeederMessage.parse("OK TIME"))
        assertEquals(FeederReply.ScheduledFed(2), FeederMessage.parse("SCHEDULED OK FED 2"))
        assertEquals(FeederReply.ScheduledSkipped, FeederMessage.parse("SCHEDULED ERR DAILY LIMIT"))
        assertEquals(FeederReply.BadCommand, FeederMessage.parse("ERR BAD SCHEDULE"))
        assertEquals(FeederReply.BadCommand, FeederMessage.parse("ERR BAD FEED"))
        assertEquals(FeederReply.Unknown("HELLO"), FeederMessage.parse("ERR UNKNOWN HELLO"))
    }

    @Test
    fun ignoresSpacesAndLineEndings() {
        assertEquals(FeederReply.Fed(2), FeederMessage.parse("OK FED 2\r"))
        assertEquals(FeederReply.Cooldown, FeederMessage.parse("  ERR COOLDOWN  "))
        assertEquals(FeederReply.TimeSet, FeederMessage.parse("OK TIME\r"))
    }

    @Test
    fun rejectsLinesThatAreNotAFeederReply() {
        val bad = listOf(
            "",
            "OK FED",          // count missing
            "OK FED abc",      // count is not a number
            "OK FED 0",        // the first feed is 1
            "OK FED -2",
            "OK SCHEDULE 07:30",   // portion missing
            "SCHEDULED OK FED",    // count missing
            "STATUS schedule=07:30 portion=2 time=21:52 feedsToday=0",
            "Bluetooth started! Waiting for messages...",
            "12.340,0.0123,-0.0456,0.9981,1.2210,-0.3050,0.0000" // a collar reading
        )

        for (line in bad) {
            assertNull("Should be rejected: \"$line\"", FeederMessage.parse(line))
        }
    }

    @Test
    fun buildsCommandsInTheFormatTheFirmwareReads() {
        assertEquals("FEED,3", FeederMessage.feedCommand(3))
        assertEquals("SCHEDULE,07:30,2", FeederMessage.scheduleCommand(7, 30, 2))
        assertEquals("SCHEDULE,21:05,1", FeederMessage.scheduleCommand(21, 5, 1))
        assertEquals("TIME,09:04:03", FeederMessage.timeCommand(9, 4, 3))
        assertEquals(true, FeederMessage.timeCommandNow().matches(Regex("TIME,\\d\\d:\\d\\d:\\d\\d")))
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
            "Schedule sent to the feeder (07:30)",
            FeederMessage.screenText(FeederReply.ScheduleSaved(7, 30, 2))
        )
        assertEquals(
            "Scheduled meal given (2 meals today)",
            FeederMessage.screenText(FeederReply.ScheduledFed(2))
        )
        assertEquals(
            "The feeder did not understand the command",
            FeederMessage.screenText(FeederReply.Unknown("HELLO"))
        )
    }
}
