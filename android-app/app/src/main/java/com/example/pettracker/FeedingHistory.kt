package com.example.pettracker

/**
 * FD-6.2: turns the feedings saved in Firebase (feeder/feedings) into the list on the
 * Feeding history page. Kept apart from the page so it can be unit tested.
 *
 * Each feeding has a "status":
 *  - "pending":   saved by the app, waiting for the feeder (FD-6.1)
 *  - "scheduled": a planned meal (hidden while it is still in the future)
 *  - "fed":       the feeder confirmed it gave the food (written by the feeder, COM-3.3)
 *  - "failed":    the feeder could not give it (for example the daily limit)
 */
object FeedingHistory {

    enum class State { GIVEN, WAITING, NO_REPLY, FAILED }

    data class Meal(val time: Long, val portion: Int, val source: String, val state: State, val grams: Int?)

    /** A meal still pending after 2 minutes probably never reached the feeder. */
    const val NO_REPLY_AFTER_MS = 2 * 60_000L

    private val given = setOf("fed", "given", "done", "ok", "success")
    private val failed = setOf("failed", "error", "skipped", "cancelled")

    /** The past meals, newest first. Planned meals that are still in the future are left out. */
    @JvmStatic
    fun fromRows(rows: List<Map<String, Any?>>, now: Long): List<Meal> =
        rows.mapNotNull { row ->
            val time = (row["time"] as? Number)?.toLong() ?: return@mapNotNull null
            if (time <= 0) return@mapNotNull null
            val status = row["status"]?.toString()?.lowercase() ?: "pending"
            if (status == "scheduled" && time > now) return@mapNotNull null
            val state = when {
                status in given -> State.GIVEN
                status in failed -> State.FAILED
                now - time > NO_REPLY_AFTER_MS -> State.NO_REPLY
                else -> State.WAITING
            }
            val portion = (row["portion"] as? Number)?.toInt() ?: 2
            val grams = (row["grams"] as? Number)?.toInt()
            Meal(time, portion, row["source"]?.toString() ?: "", state, grams)
        }.sortedByDescending { it.time }

    /** Meals grouped by day (midnight of that day), newest day first. */
    @JvmStatic
    fun byDay(meals: List<Meal>): List<Pair<Long, List<Meal>>> =
        meals.groupBy { MealLimit.startOfDay(it.time) }.toList().sortedByDescending { it.first }

    @JvmStatic
    fun portionName(portion: Int): String = when (portion) {
        1 -> "Small"
        3 -> "Large"
        else -> "Medium"
    }

    @JvmStatic
    fun sourceName(source: String): String = when (source) {
        "feed_now", "manual" -> "Feed Now"
        "scheduled" -> "Scheduled"
        else -> "Meal"
    }

    @JvmStatic
    fun stateName(state: State): String = when (state) {
        State.GIVEN -> "Given"
        State.WAITING -> "Waiting for the feeder"
        State.NO_REPLY -> "No reply from the feeder"
        State.FAILED -> "Failed"
    }
}
