package com.example.pettracker

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * COL-10.3 / COL-14.2: hours slept and wake-ups.
 * Each morning the collar adds the night to that day's entry:
 *   collarDays/<collarId>/2026-11-23 = { ..., sleepMinutes: 473, wakeUps: 1 }
 * (the night of Nov 22 to Nov 23 is saved under Nov 23).
 */
object SleepNights {

    /** Minutes asleep that night, or null if the collar sent no sleep for it. */
    fun sleepMinutes(day: Map<*, *>?): Long? = (day?.get("sleepMinutes") as? Number)?.toLong()

    fun wakeUps(day: Map<*, *>?): Long = (day?.get("wakeUps") as? Number)?.toLong() ?: 0L

    /** "7 h 53 min" or "45 min" */
    fun hoursText(minutes: Long): String =
        if (minutes < 60) "$minutes min" else "${minutes / 60} h %02d min".format(minutes % 60)

    fun wakeUpsText(count: Long): String = when (count) {
        0L -> "No wake-ups"
        1L -> "1 wake-up"
        else -> "$count wake-ups"
    }

    /** The 7 nights ending on [lastDayKey] ("2026-11-23"), oldest first. */
    fun lastSevenKeys(lastDayKey: String): List<String> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val day = Calendar.getInstance()
        day.time = format.parse(lastDayKey)!!
        day.add(Calendar.DAY_OF_YEAR, -6)
        return List(7) {
            val key = format.format(day.time)
            day.add(Calendar.DAY_OF_YEAR, 1)
            key
        }
    }

    /** Hours slept for each night (0 when there is no data), for the bar graph. */
    fun hoursPerNight(days: Map<String, Map<*, *>>, keys: List<String>): FloatArray =
        FloatArray(keys.size) { (sleepMinutes(days[keys[it]]) ?: 0L) / 60f }

    /** Short day names under the bars: "Mon", "Tue"... */
    fun labels(keys: List<String>): Array<String> {
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val name = SimpleDateFormat("EEE", Locale.getDefault())
        return Array(keys.size) { name.format(parse.parse(keys[it])!!) }
    }
}
