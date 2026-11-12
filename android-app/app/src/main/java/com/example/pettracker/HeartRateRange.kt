package com.example.pettracker

/**
 * UI-5.1: Normal resting heart rate for each species (beats per minute), used as defaults.
 * Two sources, combined so that neither one would call a healthy pet "abnormal":
 *  - Merck Veterinary Manual, "Resting Heart Rates": dogs 70 to 120, cats 120 to 140
 *  - Animal Medical Center (NYC), "Vital Signs": dogs 60 to 120 (large 60 to 90,
 *    medium 70 to 110, small 90 to 120), cats 140 to 180
 *  Defaults: dogs 60 to 120, cats 120 to 180. The ranges and sources are in the design document.
 *
 * UI-5.3: inside the range is green ("Normal"). Outside it is red ("Low" or "High").
 */
object HeartRateRange {

    enum class Level(val label: String, val color: Int) {
        LOW("Low", 0xFFDC2626.toInt()),
        NORMAL("Normal", 0xFF15803D.toInt()),
        HIGH("High", 0xFFDC2626.toInt())
    }

    /** Normal range for the pet's species. A dog's range is used if no species is saved yet. */
    @JvmStatic
    fun rangeFor(species: String?): IntRange = when (species) {
        "Cat" -> 120..180
        else -> 60..120 // Dog
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
