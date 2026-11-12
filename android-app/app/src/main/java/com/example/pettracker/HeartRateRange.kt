package com.example.pettracker

/**
 * UI-5.1: Normal resting heart rate for each species (beats per minute).
 * Source: Merck Veterinary Manual, "Resting Heart Rates" table
 * (dogs 70 to 120, cats 120 to 140). Change the numbers here if the team picks others.
 *
 * UI-5.3: below the range is "Low" (blue), inside is "Normal" (green), above is "High" (red).
 */
object HeartRateRange {

    enum class Level(val label: String, val color: Int) {
        LOW("Low", 0xFF0284C7.toInt()),
        NORMAL("Normal", 0xFF15803D.toInt()),
        HIGH("High", 0xFFDC2626.toInt())
    }

    /** Normal range for the pet's species. A dog's range is used if no species is saved yet. */
    @JvmStatic
    fun rangeFor(species: String?): IntRange = when (species) {
        "Cat" -> 120..140
        else -> 70..120 // Dog
    }

    @JvmStatic
    fun levelOf(bpm: Int, species: String?): Level {
        val range = rangeFor(species)
        return when {
            bpm < range.first -> Level.LOW
            bpm > range.last -> Level.HIGH
            else -> Level.NORMAL
        }
    }

    /** Text under the number, like "Normal for a dog: 70 to 120 BPM". */
    @JvmStatic
    fun rangeText(species: String?): String {
        val range = rangeFor(species)
        val animal = if (species == "Cat") "a cat" else "a dog"
        return "Normal for $animal: ${range.first} to ${range.last} BPM"
    }
}
