package com.example.pettracker

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * COL-13.1: watches the collar on every page and in the background, and sends one
 * notification when the pet leaves the safe zone set in Settings (home + size).
 * Started from Home after login. It stops if Android closes the app completely;
 * alerts with the app fully closed would need a server sending them.
 */
object SafeZoneWatcher {

    private var watching: Pair<DatabaseReference, ValueEventListener>? = null
    private var watchedCollar: String? = null

    fun start(context: Context, uid: String, collarId: String?) {
        if (collarId == watchedCollar && watching != null) return
        stop()
        if (collarId == null) return

        val app = context.applicationContext
        val ref = FirebaseDatabase.getInstance().reference.child("collars").child(collarId).child("latest")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                check(app, uid, CollarReading.fromMap(snapshot.value as? Map<*, *>))
            }
            override fun onCancelled(error: DatabaseError) { }
        }
        ref.addValueEventListener(listener)
        watching = ref to listener
        watchedCollar = collarId
    }

    fun stop() {
        watching?.let { (ref, listener) -> ref.removeEventListener(listener) }
        watching = null
        watchedCollar = null
    }

    private fun check(context: Context, uid: String, reading: CollarReading?) {
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) { stop(); return } // logged out

        val settings = SettingsStorage(context)
        if (!settings.safeZoneAlerts) return
        val homeLat = settings.homeLatitude
        val homeLon = settings.homeLongitude
        val status = SafeZone.status(reading, homeLat, homeLon, settings.safeRadius)

        // Saved on the phone, so closing and reopening the app does not alert again for the same trip
        val prefs = context.getSharedPreferences("pet_alerts", Context.MODE_PRIVATE)
        val decision = SafeZoneAlert.decide(status, prefs.getBoolean(KEY_ALERTED, false))
        prefs.edit().putBoolean(KEY_ALERTED, decision.alerted).apply()
        if (!decision.notify || reading == null || homeLat == null || homeLon == null) return

        val name = DatabaseHelper(context).getPetProfile(uid)?.name ?: "Your pet"
        val meters = SafeZone.distanceMeters(homeLat, homeLon, reading.latitude!!, reading.longitude!!)
        PetNotifications.show(
            context, PetNotifications.ID_SAFE_ZONE,
            "$name left the safe zone",
            "${SafeZone.formatDistance(meters)} from home. Tap to see where."
        )
    }

    private const val KEY_ALERTED = "safe_zone_alerted"
}
