package com.example.pettracker

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Calendar

/**
 * UPDATED: All application data is scoped to users/{uid}.
 * Set the Firebase Realtime Database URL in google-services.json (or Firebase console).
 */
class PetCloudRepository(uid: String) {
    // UPDATED: Explicit account root keeps one owner's data separate from another's.
    private val userRoot: DatabaseReference =
        FirebaseDatabase.getInstance().reference.child("users").child(uid)

    fun listenToLatestCollar(onValue: (Map<String, Any>?) -> Unit,
                             onError: (String) -> Unit): ValueEventListener {
        // UPDATED: Listen to the newest readings, no Bluetooth required.
        val query = userRoot.child("collar").child("readings").limitToLast(1)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val latest = snapshot.children.lastOrNull()
                @Suppress("UNCHECKED_CAST")
                onValue(latest?.value as? Map<String, Any>)
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        query.addValueEventListener(listener)
        return listener
    }

    fun collarReadingsRef(): DatabaseReference =
        userRoot.child("collar").child("readings")

    fun listenToFeedings(onValue: (List<Map<String, Any>>) -> Unit,
                         onError: (String) -> Unit): ValueEventListener {
        // UPDATED: Latest meal records refresh the app as Firebase changes.
        val query = userRoot.child("feeder").child("feedings").limitToLast(10)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rows = snapshot.children.mapNotNull {
                    @Suppress("UNCHECKED_CAST")
                    it.value as? Map<String, Any>
                }.reversed()
                onValue(rows)
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        query.addValueEventListener(listener)
        return listener
    }

    fun createFeedNow(portion: Int, onComplete: (String?, String?) -> Unit) {
        // UPDATED: Queue a cloud command and create its meal log as PENDING.
        val commands = userRoot.child("feeder").child("commands")
        val commandRef = commands.push()
        val feedingRef = userRoot.child("feeder").child("feedings").push()
        val now = System.currentTimeMillis()
        val commandId = commandRef.key ?: run {
            onComplete(null, "Could not create command ID"); return
        }
        val feedingId = feedingRef.key ?: run {
            onComplete(null, "Could not create feeding ID"); return
        }
        val command = mapOf(
            "type" to "FEED_NOW",
            "portion" to portion,
            "status" to "pending",
            "createdAt" to now,
            "feedingId" to feedingId
        )
        val feeding = mapOf(
            "time" to now,
            "portion" to portion,
            "status" to "pending",
            "source" to "feed_now",
            "commandId" to commandId
        )
        // UPDATED: Multi-location update writes command + feeding atomically.
        userRoot.updateChildren(
            mapOf(
                "feeder/commands/$commandId" to command,
                "feeder/feedings/$feedingId" to feeding
            )
        ).addOnSuccessListener { onComplete(commandId, null) }
            .addOnFailureListener { onComplete(null, it.localizedMessage ?: "Firebase write failed") }
    }

    fun saveSchedule(hour: Int, minute: Int, portion: Int,
                     onComplete: (String?, String?) -> Unit) {
        // UPDATED: Store schedule and enqueue a command for the Wi-Fi feeder.
        val commandRef = userRoot.child("feeder").child("commands").push()
        val commandId = commandRef.key ?: run {
            onComplete(null, "Could not create command ID"); return
        }
        val now = System.currentTimeMillis()
        // UPDATED: Create the next planned meal entry; feeder firmware changes its status
        // to completed/failed and records the actual dispense time when it runs.
        val nextMeal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val scheduledFeedingId = userRoot.child("feeder").child("feedings").push().key
            ?: run { onComplete(null, "Could not create scheduled feeding ID"); return }
        val schedule = mapOf(
            "enabled" to true,
            "hour" to hour,
            "minute" to minute,
            "portion" to portion,
            "updatedAt" to now,
            "nextFeedingId" to scheduledFeedingId
        )
        val plannedFeeding = mapOf(
            "time" to nextMeal,
            "scheduledTime" to nextMeal,
            "portion" to portion,
            "status" to "scheduled",
            "source" to "scheduled",
            "scheduleCommandId" to commandId
        )
        val command = mapOf(
            "type" to "SET_SCHEDULE",
            "hour" to hour,
            "minute" to minute,
            "portion" to portion,
            "status" to "pending",
            "createdAt" to System.currentTimeMillis(),
            "feedingId" to scheduledFeedingId
        )
        userRoot.updateChildren(
            mapOf(
                "feeder/schedule" to schedule,
                "feeder/commands/$commandId" to command,
                "feeder/feedings/$scheduledFeedingId" to plannedFeeding
            )
        ).addOnSuccessListener { onComplete(commandId, null) }
            .addOnFailureListener { onComplete(null, it.localizedMessage ?: "Firebase write failed") }
    }

    fun addAlert(type: String, message: String) {
        // UPDATED: Common owner-scoped alert location for app and device alerts.
        userRoot.child("alerts").push().setValue(
            mapOf("type" to type, "message" to message, "time" to System.currentTimeMillis(), "status" to "new")
        )
    }

    // UPDATED: Remove listeners from the same query objects they were attached to.
    fun removeCollarListener(listener: ValueEventListener) {
        userRoot.child("collar").child("readings").limitToLast(1).removeEventListener(listener)
    }

    fun removeFeedingsListener(listener: ValueEventListener) {
        userRoot.child("feeder").child("feedings").limitToLast(10).removeEventListener(listener)
    }
}
