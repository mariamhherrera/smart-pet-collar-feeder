package com.example.pettracker

/**
 * COM-4: the small rules behind pairing a collar.
 * No Android code here, so they can be tested with plain unit tests.
 *
 * Every collar has an ID made from its chip, like PC-3C71F2 (printed on Serial
 * when it starts, and on the sticker). It sends to Firebase under collars/<ID>.
 */
object CollarPairing {

    /** The collar sends every 5 seconds, so 30 seconds of silence = offline. */
    const val ONLINE_MS = 30_000L

    /** "Collar found" only shows collars that sent something in the last minute. */
    const val FOUND_WITHIN_MS = 60_000L

    /** One collar seen in Firebase: its ID, when it last sent (ms), and who it is linked to. */
    data class SeenCollar(val id: String, val seenAt: Long, val owner: String?)

    /**
     * Cleans up a typed ID: "pc-3c71f2", "PC 3C71F2" or "3c71f2" all give "PC-3C71F2".
     * Returns null if it is not a collar ID.
     */
    fun normalizeId(text: String): String? {
        val clean = text.trim().uppercase().replace(" ", "").replace("-", "")
        val hex = clean.removePrefix("PC")
        if (hex.length != 6) return null
        if (hex.any { it !in "0123456789ABCDEF" }) return null
        return "PC-$hex"
    }

    /** True if the collar sent something in the last 30 seconds. */
    fun isOnline(lastSeen: Long?, now: Long): Boolean =
        lastSeen != null && lastSeen > 0 && now - lastSeen < ONLINE_MS

    /** Wi-Fi strength in dBm (like -58) as a word for the owner. */
    fun signalName(dbm: Int?): String = when {
        dbm == null -> "--"
        dbm >= -60 -> "Good"
        dbm >= -72 -> "Fair"
        else -> "Weak"
    }

    /**
     * Picks the collar to show on the "Collar found" screen:
     * sent something in the last minute, not linked to another account,
     * and not one the owner said "Not my collar" to. The newest one wins.
     */
    fun pickFound(collars: List<SeenCollar>, myUid: String, skipped: Set<String>, now: Long): SeenCollar? =
        collars.filter {
            now - it.seenAt < FOUND_WITHIN_MS &&
                (it.owner == null || it.owner == myUid) &&
                it.id !in skipped
        }.maxByOrNull { it.seenAt }

    /** Seconds as minutes for the screen: "0 min", "42 min", "2 h 05 min". */
    fun formatMinutes(seconds: Long): String {
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes min"
        return "%d h %02d min".format(minutes / 60, minutes % 60)
    }
}
