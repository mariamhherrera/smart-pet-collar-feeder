package com.example.pettracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-1.3: Store pet profile.
 * Checks that a pet profile is saved on the phone, is still there when
 * the app opens again, and can only be seen by its own account.
 * Runs on an emulator or phone: right-click this file, then Run.
 */
@RunWith(AndroidJUnit4::class)
class PetProfileStorageTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun profileIsStillThereAfterReopening() {
        val profile = PetProfile("Luna", "Cat", "Siamese", 4.2, 3.0, "None")
        DatabaseHelper(context).savePetProfile("test-user-1", profile)

        // A brand-new DatabaseHelper is like opening the app again
        val loaded = DatabaseHelper(context).getPetProfile("test-user-1")

        assertEquals(profile, loaded)
    }

    @Test
    fun savingAgainUpdatesTheProfile() {
        val first = PetProfile("Milo", "Dog", "Pug", 7.0, 2.0, "None")
        val updated = PetProfile("Milo", "Dog", "Pug", 7.5, 2.5, "Allergies")
        DatabaseHelper(context).savePetProfile("test-user-2", first)
        DatabaseHelper(context).savePetProfile("test-user-2", updated)

        val loaded = DatabaseHelper(context).getPetProfile("test-user-2")

        assertEquals(updated, loaded)
    }

    @Test
    fun otherAccountsCannotSeeTheProfile() {
        val profile = PetProfile("Rex", "Dog", "Beagle", 10.5, 5.0, "None")
        DatabaseHelper(context).savePetProfile("test-user-3", profile)

        val loaded = DatabaseHelper(context).getPetProfile("a-different-user")

        assertNull(loaded)
    }
}