package com.example.pettracker

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * UI-6.2: the rules behind the Week / Month / Year graphs on the History page.
 * No Android code here, so they can be tested with plain unit tests.
 *
 * Each graph has one bar per day (week and month) or one bar per month (year).
 * Every bar has a key: "2026-11-14" for a day, "2026-11" for a month.
 */
object HistoryRange {

    enum class Range { WEEK, MONTH, YEAR }

    /**
     * A reading counts as "active" when the collar moves more than 0.1 g.
     * Same value as PEAK_HEIGHT in the simulation (simulation/method_steps.py).
     */
    const val ACTIVE_ABOVE_G = 0.1

    fun isActive(reading: CollarReading): Boolean = reading.movement() > ACTIVE_ABOVE_G

    // ---------- Keys ----------

    /** The day of a time, like "2026-11-14". Used as the key in Firebase (history/days). */
    fun dayKey(time: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))

    /** The bar a day goes in: the day itself, or its month ("2026-11") for the year graph. */
    fun barKeyForDay(range: Range, dayKey: String): String =
        if (range == Range.YEAR) dayKey.substring(0, 7) else dayKey

    /** One key per bar, oldest first, ending with today (or this month). */
    fun barKeys(range: Range, now: Long): List<String> {
        val keys = mutableListOf<String>()
        val c = Calendar.getInstance()
        c.timeInMillis = now
        if (range == Range.YEAR) {
            val month = SimpleDateFormat("yyyy-MM", Locale.US)
            repeat(12) {
                keys.add(0, month.format(c.time))
                c.add(Calendar.MONTH, -1)
            }
        } else {
            repeat(numberOfDays(range)) {
                keys.add(0, dayKey(c.timeInMillis))
                c.add(Calendar.DAY_OF_YEAR, -1)
            }
        }
        return keys
    }

    /** Midnight at the start of the first bar. Data is loaded from here to now. */
    fun rangeStart(range: Range, now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = MealLimit.startOfDay(now)
        if (range == Range.YEAR) {
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.add(Calendar.MONTH, -11)
        } else {
            c.add(Calendar.DAY_OF_YEAR, -(numberOfDays(range) - 1))
        }
        return c.timeInMillis
    }

    private fun numberOfDays(range: Range): Int = if (range == Range.WEEK) 7 else 30

    // ---------- Text ----------

    /** Words for the titles, like "Meals, past week". */
    fun title(range: Range): String = when (range) {
        Range.WEEK -> "past week"
        Range.MONTH -> "past month"
        Range.YEAR -> "past year"
    }

    /**
     * Labels under the bars.
     * Week: day names, the last one is "Today".
     * Month: a date every 7 bars (30 dates would overlap), the others are blank.
     * Year: the first letter of each month.
     */
    fun barLabels(range: Range, keys: List<String>): Array<String> {
        val parseDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parseMonth = SimpleDateFormat("yyyy-MM", Locale.US)
        return Array(keys.size) { i ->
            when (range) {
                Range.WEEK ->
                    if (i == keys.size - 1) "Today"
                    else SimpleDateFormat("EEE", Locale.getDefault()).format(parseDay.parse(keys[i])!!)
                Range.MONTH ->
                    if ((keys.size - 1 - i) % 7 == 0)
                        SimpleDateFormat("MMM d", Locale.getDefault()).format(parseDay.parse(keys[i])!!)
                    else ""
                Range.YEAR ->
                    SimpleDateFormat("MMM", Locale.getDefault()).format(parseMonth.parse(keys[i])!!).take(1)
            }
        }
    }

    // ---------- Meals ----------

    /** Meals given in each bar, oldest first. Planned or failed feedings don't count. */
    fun mealsPerBar(feedings: List<Map<*, *>>, range: Range, now: Long): IntArray {
        val keys = barKeys(range, now)
        val counts = IntArray(keys.size)
        for (row in feedings) {
            val time = (row["time"] as? Number)?.toLong() ?: continue
            if (time > now || !MealLimit.wasGiven(row)) continue
            val bar = keys.indexOf(barKeyForDay(range, dayKey(time)))
            if (bar >= 0) counts[bar]++
        }
        return counts
    }

    // ---------- Activity ----------

    /** The activity graph: percent of the time active in each bar, and over the whole range. */
    class ActivityBars(val percents: FloatArray, val overallPercent: Int)

    /**
     * [days] comes from Firebase history/days: { "2026-11-14": { readings: 120, activeReadings: 30 }, ... }
     * Returns null when there are no readings at all in the range (not enough data).
     */
    fun activityPerBar(days: Map<String, Map<*, *>>, range: Range, now: Long): ActivityBars? {
        val keys = barKeys(range, now)
        val readings = IntArray(keys.size)
        val active = IntArray(keys.size)

        for ((day, row) in days) {
            val bar = keys.indexOf(barKeyForDay(range, day))
            if (bar < 0) continue
            readings[bar] += (row["readings"] as? Number)?.toInt() ?: 0
            active[bar] += (row["activeReadings"] as? Number)?.toInt() ?: 0
        }

        if (readings.sum() == 0) return null

        val percents = FloatArray(keys.size) { i ->
            if (readings[i] == 0) 0f else 100f * active[i] / readings[i]
        }
        val overall = Math.round(100f * active.sum() / readings.sum())
        return ActivityBars(percents, overall)
    }
}
