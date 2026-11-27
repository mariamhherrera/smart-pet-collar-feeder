package com.example.pettracker

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

/**
 * Runs once when the app starts, before any page.
 *
 * Offline: Firebase keeps a copy of the data on the phone. Without internet the
 * pages still show the last values, and changes (like linking a collar) are
 * sent by themselves when the phone is back online.
 * This must be called before Firebase is used anywhere, which is why it is here.
 *
 * FD-7.1: also starts the watcher that notifies the owner when a meal fails.
 */
class PetTrackerApp : Application() {
    private lateinit var mealAlerts: MealAlertWatcher

    override fun onCreate() {
        super.onCreate()
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        mealAlerts = MealAlertWatcher(this)
        mealAlerts.start()
    }
}
