package com.example.pettracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pet profile saved online (owners/<uid>/pet/profile) comes back the same on another phone. */
class PetProfileCloudTest {

    private val max = PetProfile("Max", "Dog", "Beagle", 12.5, 3.0, "None")

    @Test
    fun profileComesBackTheSame() {
        assertEquals(max, PetProfileCloud.fromMap(PetProfileCloud.toMap(max)))
    }

    @Test
    fun wholeNumbersFromFirebaseStillWork() {
        // Firebase gives 12.0 back as the whole number 12 (a Long)
        val online = mapOf("name" to "Max", "species" to "Dog", "breed" to "Beagle",
            "weight" to 12L, "age" to 3L, "health" to "None")
        assertEquals(PetProfile("Max", "Dog", "Beagle", 12.0, 3.0, "None"), PetProfileCloud.fromMap(online))
    }

    @Test
    fun nothingOnlineMeansNoProfile() {
        assertNull(PetProfileCloud.fromMap(null))
        assertNull(PetProfileCloud.fromMap(mapOf("breed" to "Beagle")))  // no name
    }
}
