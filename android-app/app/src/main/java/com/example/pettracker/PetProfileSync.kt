package com.example.pettracker

import android.content.Context

/**
 * Keeps the pet profile the same on every phone that logs into the account.
 * The online copy wins. A phone only uploads its own copy if nothing is online yet,
 * so the first phone to save (or open the app after this update) sets the shared profile.
 */
object PetProfileSync {

    /** [onDone] gets the profile to show (online, or this phone's if offline). Runs on a background thread. */
    fun sync(context: Context, uid: String, onDone: (PetProfile?) -> Unit) {
        val database = DatabaseHelper(context.applicationContext)
        val cloud = PetCloudRepository(uid)
        cloud.loadPetProfile(
            onValue = { online ->
                if (online != null) {
                    database.savePetProfile(uid, online)        // this phone now matches the account
                    onDone(online)
                } else {
                    val mine = database.getPetProfile(uid)
                    if (mine != null) cloud.savePetProfile(mine) // first phone: share it
                    onDone(mine)
                }
            },
            onError = { onDone(database.getPetProfile(uid)) }  // offline: keep this phone's copy
        )
    }
}
