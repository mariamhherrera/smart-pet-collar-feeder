package com.example.pettracker

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.util.Calendar


/**
 * [collarId] is the collar linked to the pet (COM-4, CollarLink), or null if none is linked.
 * The collar writes under collars/<collarId>; everything else is under owners/<uid>.
 */
class PetCloudRepository @JvmOverloads constructor(uid: String, private val collarId: String? = null) {
    // "owners" matches database.rules.json.
    // ("users" was refused by the rules, so nothing could be read or written.)
    private val root: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val userRoot: DatabaseReference = root.child("owners").child(uid)

    /** The pet profile shared by every phone on this account: owners/<uid>/pet/profile */
    fun savePetProfile(profile: PetProfile) {
        userRoot.child("pet").child("profile").setValue(PetProfileCloud.toMap(profile))
    }

    fun loadPetProfile(onValue: (PetProfile?) -> Unit, onError: (String) -> Unit) {
        userRoot.child("pet").child("profile").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onValue(PetProfileCloud.fromMap(snapshot.value as? Map<*, *>))
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        })
    }

    /** The linked collar's newest reading (the collar overwrites it every 5 seconds). Null if no collar is linked. */
    private val collarLatest: DatabaseReference? =
        collarId?.let { root.child("collars").child(it).child("latest") }

    /** The newest collar reading. Gives null right away if no collar is linked. */
    fun listenToLatestCollar(onValue: (Map<String, Any>?) -> Unit,
                             onError: (String) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                onValue(snapshot.value as? Map<String, Any>)
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        if (collarLatest == null) onValue(null) else collarLatest.addValueEventListener(listener)
        return listener
    }

    /**
     * COL-2.3: the collar's totals per day, from [fromDay] to [toDay] (like "2026-11-14").
     * collarDays/<collarId>/2026-11-14 = { restingSeconds, walkingSeconds, runningSeconds }
     */
    fun loadCollarDays(fromDay: String, toDay: String,
                       onValue: (Map<String, Map<*, *>>) -> Unit, onError: (String) -> Unit) {
        val id = collarId ?: run { onValue(emptyMap()); return }
        root.child("collarDays").child(id).orderByKey().startAt(fromDay).endAt(toDay)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val days = mutableMapOf<String, Map<*, *>>()
                    for (day in snapshot.children) {
                        val row = day.value as? Map<*, *> ?: continue
                        days[day.key ?: continue] = row
                    }
                    onValue(days)
                }
                override fun onCancelled(error: DatabaseError) { onError(error.message) }
            })
    }

    /** COL-6: reads the newest collar reading once (used by "Set home" in Settings). */
    fun loadLatestCollar(onValue: (Map<String, Any>?) -> Unit, onError: (String) -> Unit) {
        val latest = collarLatest ?: run { onValue(null); return }
        latest.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                onValue(snapshot.value as? Map<String, Any>)
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        })
    }

    /**
     * UI-6.1: Saves a reading in the history with its time, so the graphs can show it later.
     * collar/latest only keeps the newest one. Same time = already saved, so it is skipped.
     */
    fun saveReadingToHistory(reading: CollarReading) {
        if (reading.timeMillis <= 0 || reading.timeMillis == lastSavedTime) return
        lastSavedTime = reading.timeMillis
        userRoot.child("collar").child("readings").child(reading.timeMillis.toString())
            .setValue(reading.toHistoryRow())

        // UI-6.1: also count it in that day's summary, so the month and year graphs
        // only load one small row per day instead of every reading.
        // history/days/2026-11-14 = { readings: 120, activeReadings: 30 }
        val count = mutableMapOf<String, Any>("readings" to ServerValue.increment(1L))
        if (HistoryRange.isActive(reading)) count["activeReadings"] = ServerValue.increment(1L)
        userRoot.child("history").child("days").child(HistoryRange.dayKey(reading.timeMillis))
            .updateChildren(count)
    }
    private var lastSavedTime = 0L

    /** UI-6.2: Loads the day summaries from [fromDay] to [toDay] (like "2026-11-14"). */
    fun loadDaySummaries(fromDay: String, toDay: String,
                         onValue: (Map<String, Map<*, *>>) -> Unit, onError: (String) -> Unit) {
        userRoot.child("history").child("days").orderByKey().startAt(fromDay).endAt(toDay)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val days = mutableMapOf<String, Map<*, *>>()
                    for (day in snapshot.children) {
                        val row = day.value as? Map<*, *> ?: continue
                        days[day.key ?: continue] = row
                    }
                    onValue(days)
                }
                override fun onCancelled(error: DatabaseError) { onError(error.message) }
            })
    }

    /**
     * Bluetooth backup: a reading that came over Bluetooth goes to collar/latest,
     * the same place the Wi-Fi collar writes, so every screen sees it the same way.
     */
    fun writeLatestFromBluetooth(reading: CollarReading) {
        val row = reading.toHistoryRow().toMutableMap()
        row["receivedAt"] = reading.timeMillis
        row["source"] = "bluetooth_backup"
        // updateChildren, not setValue: keeps fields the Wi-Fi collar wrote (like batteryPercent)
        collarLatest?.updateChildren(row)
    }

    /** History for the graphs (UI-6.2): the last [count] saved readings, oldest first. */
    fun listenToReadings(count: Int, onValue: (List<CollarReading>) -> Unit,
                         onError: (String) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onValue(snapshot.children.mapNotNull { CollarReading.fromMap(it.value as? Map<*, *>) })
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        userRoot.child("collar").child("readings").limitToLast(count).addValueEventListener(listener)
        return listener
    }

    /**
     * UI-6.1: Loads the readings of one day (from [dayStart] to [dayEnd], in ms).
     * Each reading is saved under its time in ms (13 digits), so the keys are in time order.
     */
    fun loadReadingsBetween(dayStart: Long, dayEnd: Long,
                            onValue: (List<CollarReading>) -> Unit, onError: (String) -> Unit) {
        userRoot.child("collar").child("readings").orderByKey()
            .startAt(dayStart.toString()).endAt(dayEnd.toString())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    onValue(snapshot.children.mapNotNull { CollarReading.fromMap(it.value as? Map<*, *>) })
                }
                override fun onCancelled(error: DatabaseError) { onError(error.message) }
            })
    }

    /** UI-6.1: Loads the meals of one day, using the "time" saved with each feeding. */
    fun loadFeedingsBetween(dayStart: Long, dayEnd: Long,
                            onValue: (List<Map<String, Any>>) -> Unit, onError: (String) -> Unit) {
        userRoot.child("feeder").child("feedings").orderByChild("time")
            .startAt(dayStart.toDouble()).endAt(dayEnd.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    onValue(snapshot.children.mapNotNull {
                        @Suppress("UNCHECKED_CAST")
                        it.value as? Map<String, Any>
                    })
                }
                override fun onCancelled(error: DatabaseError) { onError(error.message) }
            })
    }

    fun removeReadingsListener(count: Int, listener: ValueEventListener) {
        userRoot.child("collar").child("readings").limitToLast(count).removeEventListener(listener)
    }

    /** UI-8.2: copy of the settings, so the feeder can read the meal limit. */
    fun saveSettings(settings: Map<String, Any>, onComplete: (String?) -> Unit) {
        userRoot.child("settings").setValue(settings)
            .addOnSuccessListener { onComplete(null) }
            .addOnFailureListener { onComplete(it.localizedMessage ?: "Firebase write failed") }
    }

    fun collarReadingsRef(): DatabaseReference =
        userRoot.child("collar").child("readings")
    fun collarLatestRef(): DatabaseReference? = collarLatest

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
                         onError: (String) -> Unit): ValueEventListener =
        listenToFeedings(10, onValue, onError)

    /** The last [count] feedings, newest first. */
    fun listenToFeedings(count: Int, onValue: (List<Map<String, Any>>) -> Unit,
                         onError: (String) -> Unit): ValueEventListener {
        val query = userRoot.child("feeder").child("feedings").limitToLast(count)
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
            "command" to "FEED",
            "source" to "manual",
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
            "command" to "SCHEDULE",
            "source" to "app",
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
        collarLatest?.removeEventListener(listener)
    }

    fun removeFeedingsListener(listener: ValueEventListener) = removeFeedingsListener(10, listener)

    fun removeFeedingsListener(count: Int, listener: ValueEventListener) {
        userRoot.child("feeder").child("feedings").limitToLast(count).removeEventListener(listener)
    }
}
