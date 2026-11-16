package com.example.pettracker

/**
 * COM-3.3: what the feeder writes to owners/<you>/feeder/status every 30 seconds over Wi-Fi:
 * connected, lastSeen (server time in ms), foodPercent, feedsToday, mealLimit.
 */
object FeederStatus {

    /** The feeder writes every 30 s, so 90 s of silence means it is off or has no Wi-Fi. */
    const val ONLINE_MS = 90_000L

    /** Online only if it said so recently (it cannot write "offline" once it is unplugged). */
    @JvmStatic
    fun isOnline(status: Map<String, Any?>?, now: Long): Boolean {
        if (status?.get("connected") != true) return false
        val lastSeen = (status["lastSeen"] as? Number)?.toLong() ?: return false
        return now - lastSeen < ONLINE_MS
    }
}
