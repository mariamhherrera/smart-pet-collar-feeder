package com.example.pettracker

import android.content.Context
import com.example.pettracker.feeder.ScheduleActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * FD-7.1: watches the signed-in owner's meals (owners/{uid}/feeder/feedings) while the app
 * is running, and shows one notification for each new failed meal (rules in [MealAlerts]).
 *
 * Started once from [PetTrackerApp], so it keeps working on every page and in the background
 * until Android closes the app. Alerts while the app is fully closed would need Firebase Cloud
 * Messaging and a server function (future work).
 */
class MealAlertWatcher(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("meal_alerts", Context.MODE_PRIVATE)
    private var query: Query? = null
    private var listener: ValueEventListener? = null
    private var watchingUid: String? = null

    /** Follows sign-in and sign-out: watches the meals of whoever is signed in. */
    fun start() {
        FirebaseAuth.getInstance().addAuthStateListener { auth -> watch(auth.currentUser?.uid) }
    }

    private fun watch(uid: String?) {
        if (uid == watchingUid) return
        listener?.let { query?.removeEventListener(it) }
        query = null; listener = null; watchingUid = uid
        if (uid == null) return

        val q = FirebaseDatabase.getInstance().reference
            .child("owners").child(uid).child("feeder").child("feedings").limitToLast(MEALS_TO_CHECK)
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rows = snapshot.children.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val values = child.value as? Map<String, Any?> ?: return@mapNotNull null
                    id to values
                }
                announce(rows)
            }
            override fun onCancelled(error: DatabaseError) { /* no alerts without access */ }
        }
        q.addValueEventListener(l)
        query = q; listener = l
    }

    private fun announce(rows: List<Pair<String, Map<String, Any?>>>) {
        val shown = shownIds()
        val alerts = MealAlerts.newAlerts(rows, shown.toSet(), System.currentTimeMillis())
        if (alerts.isEmpty()) return
        // One notification at a time: a newer failed meal replaces the older alert (COM-6.1 rule)
        val latest = alerts.last()
        val text = if (alerts.size == 1) latest.text else "${latest.text} (${alerts.size} meals failed)"
        PetNotifications.show(appContext, PetNotifications.ID_FAILED_MEAL, latest.title, text, ScheduleActivity::class.java)
        saveShownIds(MealAlerts.trim(shown + alerts.map { it.id }))
    }

    // Remembered as one comma-separated string so the order (oldest first) is kept.
    private fun shownIds(): List<String> =
        prefs.getString(KEY_SHOWN, "").orEmpty().split(",").filter { it.isNotBlank() }

    private fun saveShownIds(ids: List<String>) {
        prefs.edit().putString(KEY_SHOWN, ids.joinToString(",")).apply()
    }

    companion object {
        private const val MEALS_TO_CHECK = 20
        private const val KEY_SHOWN = "shown_ids"
    }
}
