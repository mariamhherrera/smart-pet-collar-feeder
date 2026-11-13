package com.example.pettracker

data class CollarMotion(
    val timeSeconds: Double,
    val ax: Double, val ay: Double, val az: Double,
    val gx: Double, val gy: Double, val gz: Double,
    val heartRateBpm: Int? = null
)

object CollarProtocol {
    /**
     * Parses the current firmware's seven-column CSV record.
     * Header lines and non-motion status messages return null.
     */
    fun parseMotion(line: String): CollarMotion? {
        val parts = line.trim().split(',')
        if (parts.size !in 7..8) return null
        val values = parts.map { it.trim().toDoubleOrNull() ?: return null }
        return CollarMotion(
            values[0], values[1], values[2], values[3],
            values[4], values[5], values[6],
            // UPDATED: Ignore missing/invalid/out-of-range BPM instead of rejecting motion data.
            parts.getOrNull(7)?.trim()?.toIntOrNull()?.takeIf { it in 30..240 }
        )
    }
}