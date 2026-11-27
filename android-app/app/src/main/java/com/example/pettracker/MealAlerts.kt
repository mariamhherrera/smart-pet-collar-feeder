package com.example.pettracker

/**
 * FD-7.1: decides which meals in Firebase (feeder/feedings) need a "meal failed" notification.
 * Kept apart from Android so it can be unit tested.
 *
 * The feeder marks a meal "failed" with a result such as:
 *  - "ERR NO FOOD"     less than 5 g reached the bowl (empty hopper or jammed plate) (FD-3.3)
 *  - "ERR OFFLINE"     the feeder was offline when the meal was due
 *  - "ERR DAILY LIMIT" / "ERR COOLDOWN"  the feeder's own safety rules skipped the meal on purpose
 *
 * Rules:
 *  - Only failed meals get an alert, never a meal that worked.
 *  - The safety-rule skips get no alert: nothing went wrong, and Feed Now would not help.
 *  - Each meal gets one alert at most ([alreadyShown] holds the ids already notified).
 *  - Only recent failures: opening the app for the first time does not alert about old meals.
 */
object MealAlerts {

    data class Alert(val id: String, val title: String, val text: String)

    /** Failures older than this are not announced. */
    const val RECENT_MS = 30 * 60_000L

    private val failedStatuses = setOf("failed", "error")
    private val ruleSkips = setOf("ERR DAILY LIMIT", "ERR COOLDOWN")

    /** The reason shown to the owner, or null when this result needs no alert. */
    @JvmStatic
    fun reason(result: String?): String? {
        val r = result?.trim()?.uppercase() ?: ""
        return when {
            r in ruleSkips -> null
            r.startsWith("ERR NO FOOD") -> "No food came out. The hopper may be empty or the plate is jammed."
            r.startsWith("ERR OFFLINE") -> "The feeder was offline, so the meal was not given."
            else -> "The feeder could not give this meal."
        }
    }

    /**
     * The alerts to show now. [rows] are (feeding id, feeding values) pairs from Firebase.
     * A row is announced when its status is failed, its reason needs an alert, it is recent,
     * and its id is not in [alreadyShown].
     */
    @JvmStatic
    fun newAlerts(rows: List<Pair<String, Map<String, Any?>>>, alreadyShown: Set<String>, now: Long): List<Alert> =
        rows.mapNotNull { (id, row) ->
            if (id in alreadyShown) return@mapNotNull null
            val status = row["status"]?.toString()?.lowercase() ?: return@mapNotNull null
            if (status !in failedStatuses) return@mapNotNull null
            val text = reason(row["result"]?.toString()) ?: return@mapNotNull null
            // updatedAt = when the feeder wrote the result; time = when the meal was due.
            val whenFailed = (row["updatedAt"] as? Number)?.toLong() ?: (row["time"] as? Number)?.toLong() ?: 0L
            if (whenFailed <= 0 || now - whenFailed > RECENT_MS) return@mapNotNull null
            val portion = FeedingHistory.portionName((row["portion"] as? Number)?.toInt() ?: 2).lowercase()
            val source = if (row["source"]?.toString() == "scheduled") "scheduled" else "Feed Now"
            Alert(id, "Meal not given", "The $source $portion meal failed. $text Try Feed Now.")
        }

    /** Keeps the remembered ids from growing forever: only the newest [max] are kept. */
    @JvmStatic
    fun trim(ids: List<String>, max: Int = 50): List<String> = if (ids.size <= max) ids else ids.takeLast(max)
}
