package com.example.pettracker.bluetooth

/** One answer from the feeder. */
sealed class FeederReply {
    /** A portion was given. [feedsToday] counts this feed too, so the first one is 1. */
    data class Fed(val feedsToday: Int) : FeederReply()

    /** Ignored: the last feed was less than 3 seconds ago (double tap). */
    object Cooldown : FeederReply()

    /** Ignored: the maximum number of feeds for today was already reached. */
    object DailyLimit : FeederReply()

    /** The feeder did not recognize [command]. */
    data class Unknown(val command: String) : FeederReply()
}

/**
 * The messages between the app and the feeder, as written by
 * firmware/feeder/feeder_bluetooth/feeder_bluetooth.ino:
 *
 *     app sends:      FEED
 *     feeder replies: OK FED 3  |  ERR COOLDOWN  |  ERR DAILY LIMIT  |  ERR UNKNOWN <message>
 *
 * See the "Feeder commands" section of docs/bluetooth_protocol.md.
 */
object FeederMessage {

    /** The Bluetooth name the feeder firmware gives itself (SerialBT.begin in the .ino). */
    const val DEVICE_NAME = "PetFeeder_ESP32"

    /** The command that gives one portion. */
    const val FEED = "FEED"

    /** Returns null when the line is not a feeder reply. */
    fun parse(line: String): FeederReply? {
        val text = line.trim()

        if (text.startsWith("OK FED ")) {
            val count = text.removePrefix("OK FED ").trim().toIntOrNull() ?: return null
            if (count < 1) return null
            return FeederReply.Fed(count)
        }
        if (text == "ERR COOLDOWN") return FeederReply.Cooldown
        if (text == "ERR DAILY LIMIT") return FeederReply.DailyLimit
        if (text.startsWith("ERR UNKNOWN")) {
            return FeederReply.Unknown(text.removePrefix("ERR UNKNOWN").trim())
        }
        return null
    }

    /** The sentence shown to the owner for each reply. */
    fun screenText(reply: FeederReply): String =
        when (reply) {
            is FeederReply.Fed ->
                if (reply.feedsToday == 1) "Food given (1 meal today)"
                else "Food given (${reply.feedsToday} meals today)"
            FeederReply.Cooldown -> "Please wait a few seconds before feeding again"
            FeederReply.DailyLimit -> "Daily limit reached, no more food today"
            is FeederReply.Unknown -> "The feeder did not understand the command"
        }
}
