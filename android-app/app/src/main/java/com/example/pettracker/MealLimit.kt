package com.example.pettracker

import java.util.Calendar

/**
 * UI-8.3: The daily meal limit from Settings, applied before Feed Now.
 * Counts the meals given today from the feeding history in Firebase.
 */
object MealLimit {

    /** These do not count as a meal given: planned, failed or skipped feedings. */
    private val notGiven = setOf("scheduled", "failed", "skipped", "error", "cancelled")

    /** Number of meals given since midnight (phone time). */
    @JvmStatic
    fun mealsToday(feedings: List<Map<*, *>>, now: Long = System.currentTimeMillis()): Int {
        val midnight = startOfDay(now)
        return feedings.count { row ->
            val time = (row["time"] as? Number)?.toLong() ?: (row["time"] as? String)?.toLongOrNull() ?: 0L
            val status = row["status"]?.toString()?.lowercase() ?: ""
            time in midnight..now && status !in notGiven
        }
    }

    @JvmStatic
    fun canFeed(mealsToday: Int, limit: Int): Boolean = mealsToday < limit

    /** True if this feeding was really given (used by the History graphs, UI-6.2). */
    @JvmStatic
    fun wasGiven(row: Map<*, *>): Boolean = row["status"]?.toString()?.lowercase() !in notGiven

    /** Meals given on each of the last [days] days, oldest first (for the History graph). */
    @JvmStatic
    fun mealsPerDay(feedings: List<Map<*, *>>, days: Int, now: Long = System.currentTimeMillis()): IntArray {
        val counts = IntArray(days)
        val today = startOfDay(now)
        for (row in feedings) {
            val time = (row["time"] as? Number)?.toLong() ?: continue
            val status = row["status"]?.toString()?.lowercase() ?: ""
            if (status in notGiven || time > now) continue
            val daysAgo = daysBetween(startOfDay(time), today)
            if (daysAgo in 0 until days) counts[days - 1 - daysAgo]++
        }
        return counts
    }

    @JvmStatic
    fun startOfDay(time: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = time
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Whole days from one midnight to another (works across daylight saving changes). */
    private fun daysBetween(fromMidnight: Long, toMidnight: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = fromMidnight
        var days = 0
        while (c.timeInMillis < toMidnight && days < 400) {
            c.add(Calendar.DAY_OF_YEAR, 1)
            days++
        }
        return days
    }
}
