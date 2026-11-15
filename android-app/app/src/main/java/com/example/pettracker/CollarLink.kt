package com.example.pettracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * COM-4: which collar is linked to the pet.
 *
 * In Firebase:
 *   owners/<uid>/pet/collarId = "PC-3C71F2"   (the owner's side of the link)
 *   collars/<ID>/owner        = "<uid>"       (so no one else can link it)
 *   collars/<ID>/latest, seenAt               (written by the collar)
 *
 * The ID is also saved on the phone, so every page knows it right away, even offline.
 */
class CollarLink(context: Context, private val uid: String) {

    private val phone = context.getSharedPreferences("collar_link", Context.MODE_PRIVATE)
    private val database = FirebaseDatabase.getInstance().reference

    /** The linked collar's ID, or null if no collar is linked. */
    val collarId: String?
        get() = phone.getString(key(), null)

    private fun key() = "collarId_$uid"

    private fun saveOnPhone(id: String?) {
        if (id == null) phone.edit().remove(key()).apply()
        else phone.edit().putString(key(), id).apply()
    }

    /** Reads the link from Firebase (it may have changed on another phone). Gives the ID or null. */
    fun refresh(onDone: (String?) -> Unit) {
        database.child("owners").child(uid).child("pet").child("collarId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val id = snapshot.value as? String
                    saveOnPhone(id)
                    onDone(id)
                }
                override fun onCancelled(error: DatabaseError) { onDone(collarId) }
            })
    }

    /**
     * Links the collar to the pet. If another collar was linked, it is unlinked.
     * [onDone] gets null when it worked, or a message for the owner.
     */
    fun link(id: String, onDone: (String?) -> Unit) {
        // First check that the collar is not someone else's
        database.child("collars").child(id).child("owner")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val owner = snapshot.value as? String
                    if (owner != null && owner != uid) {
                        onDone("This collar is linked to another account. Unlink it there first.")
                        return
                    }
                    val changes = mutableMapOf<String, Any?>(
                        "owners/$uid/pet/collarId" to id,
                        "collars/$id/owner" to uid
                    )
                    val old = collarId
                    if (old != null && old != id) changes["collars/$old/owner"] = null
                    save(changes, id, onDone)
                }
                override fun onCancelled(error: DatabaseError) {
                    onDone("Could not reach Firebase: ${error.message}")
                }
            })
    }

    /** Unlinks the collar from the pet. [onDone] gets null when it worked, or a message. */
    fun unlink(onDone: (String?) -> Unit) {
        val id = collarId ?: run { onDone(null); return }
        val changes = mapOf<String, Any?>(
            "owners/$uid/pet/collarId" to null,
            "collars/$id/owner" to null
        )
        save(changes, null, onDone)
    }

    /**
     * Writes all [changes] together (all or nothing), and saves [newId] on the phone.
     * Offline: Firebase sends the change when the phone is back online,
     * so after 8 seconds we stop waiting and say so.
     */
    private fun save(changes: Map<String, Any?>, newId: String?, onDone: (String?) -> Unit) {
        val oldId = collarId
        saveOnPhone(newId)
        var answered = false
        val waitTooLong = Handler(Looper.getMainLooper())
        waitTooLong.postDelayed({
            if (!answered) {
                answered = true
                onDone(null)   // kept on the phone, Firebase will send it later
            }
        }, 8_000L)

        database.updateChildren(changes)
            .addOnSuccessListener {
                if (answered) return@addOnSuccessListener
                answered = true
                waitTooLong.removeCallbacksAndMessages(null)
                onDone(null)
            }
            .addOnFailureListener { error ->
                saveOnPhone(oldId)   // did not work: put the old link back
                if (answered) return@addOnFailureListener
                answered = true
                waitTooLong.removeCallbacksAndMessages(null)
                onDone("Could not save the link: ${error.localizedMessage ?: "Firebase error"}")
            }
    }

    // ---------- Finding collars (pairing screen) ----------

    /**
     * Listens for collars that sent something in the last minute.
     * The collar writes collars/<ID>/seenAt every 5 seconds.
     */
    fun listenForCollars(onValue: (List<CollarPairing.SeenCollar>) -> Unit,
                         onError: (String) -> Unit): Pair<Query, ValueEventListener> {
        val since = System.currentTimeMillis() - CollarPairing.FOUND_WITHIN_MS
        val query = database.child("collars").orderByChild("seenAt").startAt(since.toDouble())
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onValue(snapshot.children.mapNotNull { seenCollar(it) })
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        }
        query.addValueEventListener(listener)
        return Pair(query, listener)
    }

    /** Reads one collar (when the owner types its ID). Gives null if it never connected. */
    fun loadCollar(id: String, onValue: (CollarPairing.SeenCollar?, CollarReading?) -> Unit,
                   onError: (String) -> Unit) {
        database.child("collars").child(id).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onValue(seenCollar(snapshot), CollarReading.fromMap(snapshot.child("latest").value as? Map<*, *>))
            }
            override fun onCancelled(error: DatabaseError) { onError(error.message) }
        })
    }

    /** Listens to a collar's newest reading (pairing and pet profile pages). */
    fun listenToCollar(id: String, onValue: (CollarReading?) -> Unit): Pair<Query, ValueEventListener> {
        val ref = database.child("collars").child(id).child("latest")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onValue(CollarReading.fromMap(snapshot.value as? Map<*, *>))
            }
            override fun onCancelled(error: DatabaseError) { onValue(null) }
        }
        ref.addValueEventListener(listener)
        return Pair(ref, listener)
    }

    private fun seenCollar(snapshot: DataSnapshot): CollarPairing.SeenCollar? {
        val id = snapshot.key ?: return null
        val seenAt = CollarReading.number(snapshot.child("seenAt").value)?.toLong() ?: return null
        return CollarPairing.SeenCollar(id, seenAt, snapshot.child("owner").value as? String)
    }
}
