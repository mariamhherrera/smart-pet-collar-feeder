package com.example.pettracker.bluetooth

import java.util.Calendar
import java.util.Locale

/** One answer from the feeder. */
sealed class FeederReply {
    /** A portion was given. [feedsToday] counts this feed too, so the first one is 1. */
    data class Fed(val feedsToday: Int) : FeederReply()

    /** Ignored: the last feed was less than 3 seconds ago (double tap). */
    object Cooldown : FeederReply()

    /** Ignored: the maximum number of feeds for today was already reached. */
    object DailyLimit : FeederReply()

    /** The feeder saved the schedule. */
    data class ScheduleSaved(val hour: Int, val minute: Int, val portion: Int) : FeederReply()

    /** The feeder's clock was set. */
    object TimeSet : FeederReply()

    /** UI-8.3: the feeder saved the daily meal limit from Settings. */
    data class LimitSet(val mealsPerDay: Int) : FeederReply()

    /** The feeder gave the scheduled meal by itself. */
    data class ScheduledFed(val feedsToday: Int) : FeederReply()

    /** The scheduled meal was skipped because the daily limit was reached. */
    object ScheduledSkipped : FeederReply()

    /** The feeder refused a command because its format was wrong. */
    object BadCommand : FeederReply()

    /** The feeder did not recognize [command]. */
    data class Unknown(val command: String) : FeederReply()
}

/**
 * The messages between the app and the feeder, as written by
 * firmware/feeder/feeder_bluetooth/feeder_bluetooth.ino:
 *
 *     app sends:      FEED  |  FEED,2  |  SCHEDULE,07:30,2  |  TIME,21:45:00  |  LIMIT,3
 *     feeder replies: OK FED 3  |  ERR COOLDOWN  |  ERR DAILY LIMIT  |  OK SCHEDULE 07:30 2
 *                     OK TIME  |  OK LIMIT 3  |  ERR BAD ...  |  ERR UNKNOWN <message>
 *     feeder sends by itself at the scheduled time:
 *                     SCHEDULED OK FED 2  |  SCHEDULED ERR DAILY LIMIT
 *
 * Portions: 1 = small, 2 = medium, 3 = large.
 * See the "Feeder commands" section of docs/bluetooth_protocol.md.
 */
object FeederMessage {

    /** The Bluetooth name the feeder firmware gives itself (SerialBT.begin in the .ino). */
    const val DEVICE_NAME = "PetFeeder_ESP32"

    /** The command that gives one medium portion. */
    const val FEED = "FEED"

    /** Gives one portion right away, for example FEED,3. */
    @JvmStatic
    fun feedCommand(portion: Int): String = "FEED,$portion"

    /** Saves the daily feeding time and portion, for example SCHEDULE,07:30,2. */
    @JvmStatic
    fun scheduleCommand(hour: Int, minute: Int, portion: Int): String =
        String.format(Locale.US, "SCHEDULE,%02d:%02d,%d", hour, minute, portion)

    /** Tells the feeder what time it is, for example TIME,21:45:00. */
    @JvmStatic
    fun timeCommand(hour: Int, minute: Int, second: Int): String =
        String.format(Locale.US, "TIME,%02d:%02d:%02d", hour, minute, second)

    /** UI-8.3: the most meals per day (1 to 6), from Settings. For example LIMIT,3. */
    @JvmStatic
    fun limitCommand(mealsPerDay: Int): String = "LIMIT,$mealsPerDay"

    /** The TIME command with the phone's current time. */
    @JvmStatic
    fun timeCommandNow(): String {
        val now = Calendar.getInstance()
        return timeCommand(
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND)
        )
    }

    /** Returns null when the line is not a feeder reply. */
    @JvmStatic
    fun parse(line: String): FeederReply? {
        val text = line.trim()

        if (text.startsWith("SCHEDULED ")) {
            val result = text.removePrefix("SCHEDULED ").trim()
            if (result == "ERR DAILY LIMIT") return FeederReply.ScheduledSkipped
            val fed = parseFed(result) ?: return null
            return FeederReply.ScheduledFed(fed)
        }
        if (text.startsWith("OK FED")) {
            val fed = parseFed(text) ?: return null
            return FeederReply.Fed(fed)
        }
        if (text.startsWith("OK SCHEDULE ")) {
            // OK SCHEDULE 07:30 2
            val parts = text.removePrefix("OK SCHEDULE ").trim().split(' ', ':')
            if (parts.size != 3) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            val portion = parts[2].toIntOrNull() ?: return null
            return FeederReply.ScheduleSaved(hour, minute, portion)
        }
        if (text == "OK TIME") return FeederReply.TimeSet
        if (text.startsWith("OK LIMIT ")) {
            val limit = text.removePrefix("OK LIMIT ").trim().toIntOrNull() ?: return null
            return FeederReply.LimitSet(limit)
        }
        if (text == "ERR COOLDOWN") return FeederReply.Cooldown
        if (text == "ERR DAILY LIMIT") return FeederReply.DailyLimit
        if (text.startsWith("ERR BAD")) return FeederReply.BadCommand
        if (text.startsWith("ERR UNKNOWN")) {
            return FeederReply.Unknown(text.removePrefix("ERR UNKNOWN").trim())
        }
        return null
    }

    /** "OK FED 3" -> 3, or null when the count is missing or wrong. */
    private fun parseFed(text: String): Int? {
        if (!text.startsWith("OK FED ")) return null
        val count = text.removePrefix("OK FED ").trim().toIntOrNull() ?: return null
        return if (count >= 1) count else null
    }

    /** The sentence shown to the owner for each reply. */
    @JvmStatic
    fun screenText(reply: FeederReply): String =
        when (reply) {
            is FeederReply.Fed -> "Food given (${meals(reply.feedsToday)} today)"
            FeederReply.Cooldown -> "Please wait a few seconds before feeding again"
            FeederReply.DailyLimit -> "Daily limit reached, no more food today"
            is FeederReply.ScheduleSaved -> String.format(
                Locale.US, "Schedule sent to the feeder (%02d:%02d)", reply.hour, reply.minute
            )
            FeederReply.TimeSet -> "Feeder clock set"
            is FeederReply.LimitSet -> "Meal limit set to ${meals(reply.mealsPerDay)} a day"
            is FeederReply.ScheduledFed -> "Scheduled meal given (${meals(reply.feedsToday)} today)"
            FeederReply.ScheduledSkipped -> "Scheduled meal skipped, daily limit reached"
            FeederReply.BadCommand -> "The feeder refused the command"
            is FeederReply.Unknown -> "The feeder did not understand the command"
        }

    private fun meals(count: Int) = if (count == 1) "1 meal" else "$count meals"
}
