package com.example.pettracker

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Calendar


class PetCloudRepository(uid: String) {
    private val userRoot: DatabaseReference =
        FirebaseDatabase.getInstance().reference.child("owners").child(uid) // UPDATED: match ESP32 firmware and database.rules.json owner path

    fun listenToLatestCollar(onValue: (Map<String, Any>?) -> Unit,
                             onError: (String) -> Unit): ValueEventListener {
        val query = userRoot.child("collar").child("latest")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                onValue(snapshot.value as? Map<String, Any>)
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        query.addValueEventListener(listener)
        return listener
    }

    fun collarLatestRef(): DatabaseReference =
        userRoot.child("collar").child("latest")

    fun listenToCollarStatus(onValue: (Map<String, Any>?) -> Unit,
                             onError: (String) -> Unit): ValueEventListener =
        listenToDeviceStatus("collar", onValue, onError)

    fun listenToFeederStatus(onValue: (Map<String, Any>?) -> Unit,
                             onError: (String) -> Unit): ValueEventListener =
        listenToDeviceStatus("feeder", onValue, onError)

    private fun listenToDeviceStatus(device: String,
                                     onValue: (Map<String, Any>?) -> Unit,
                                     onError: (String) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                onValue(snapshot.value as? Map<String, Any>)
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        userRoot.child(device).child("status").addValueEventListener(listener)
        return listener
    }

    fun removeCollarStatusListener(listener: ValueEventListener) {
        userRoot.child("collar").child("status").removeEventListener(listener)
    }

    fun removeFeederStatusListener(listener: ValueEventListener) {
        userRoot.child("feeder").child("status").removeEventListener(listener)
    }

    fun listenToFeedings(onValue: (List<Map<String, Any>>) -> Unit,
                         onError: (String) -> Unit): ValueEventListener {
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
        val commandRef = userRoot.child("feeder").child("commands").push()
        val commandId = commandRef.key ?: run {
            onComplete(null, "Could not create command ID"); return
        }
        val now = System.currentTimeMillis()
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

    fun updateFeedingStatus(feedingId: String, status: String) {
        userRoot.child("feeder").child("feedings").child(feedingId).updateChildren(
            mapOf("status" to status, "updatedAt" to System.currentTimeMillis())
        )
    }

    fun addAlert(type: String, message: String) {
        userRoot.child("alerts").push().setValue(
            mapOf("type" to type, "message" to message, "time" to System.currentTimeMillis(), "status" to "new")
        )
    }

    fun removeCollarListener(listener: ValueEventListener) {
        userRoot.child("collar").child("latest").removeEventListener(listener)
    }

    fun removeFeedingsListener(listener: ValueEventListener) {
        userRoot.child("feeder").child("feedings").limitToLast(10).removeEventListener(listener)
    }
}
