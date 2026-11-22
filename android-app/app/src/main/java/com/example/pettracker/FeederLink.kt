package com.example.pettracker

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * COM-7: which feeder is paired with the owner.
 *   owners/<uid>/feederId = "PF-C9EDE0"   (the owner's side)
 *   feeders/<ID>/owner    = "<uid>"       (the feeder reads this)
 *   feeders/<ID>/seenAt                   (the feeder writes this every 30 s)
 */
class FeederLink(private val uid: String) {

    private val db = FirebaseDatabase.getInstance().reference

    /** Gives the paired feeder's ID, or null if not paired. */
    fun load(onDone: (String?) -> Unit) {
        db.child("owners").child(uid).child("feederId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { onDone(s.value as? String) }
                override fun onCancelled(e: DatabaseError) { onDone(null) }
            })
    }

    /** Finds a feeder that is on right now (seen in the last minute, not someone else's). */
    fun findFeederThatIsOn(onDone: (String?) -> Unit) {
        val since = System.currentTimeMillis() - 60_000L
        db.child("feeders").orderByChild("seenAt").startAt(since.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val found = s.children.filter {
                        val owner = it.child("owner").value as? String
                        owner == null || owner == uid
                    }.maxByOrNull { (it.child("seenAt").value as? Number)?.toLong() ?: 0L }
                    onDone(found?.key)
                }
                override fun onCancelled(e: DatabaseError) { onDone(null) }
            })
    }

    /** "pf-c9ede0" or "c9ede0" gives "PF-C9EDE0". Null if it isn't a feeder ID. */
    fun cleanId(text: String): String? {
        val hex = text.trim().uppercase().replace(" ", "").replace("-", "").removePrefix("PF")
        if (hex.length != 6 || hex.any { it !in "0123456789ABCDEF" }) return null
        return "PF-$hex"
    }

    /** onDone gets null if it worked, or a message for the owner. */
    fun pair(id: String, onDone: (String?) -> Unit) {
        db.child("feeders").child(id).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.child("seenAt").exists()) {
                    onDone("No feeder with ID $id has connected yet. Check the ID and that the feeder is on Wi-Fi.")
                    return
                }
                val owner = s.child("owner").value as? String
                if (owner != null && owner != uid) {
                    onDone("This feeder is linked to another account. Unpair it there first.")
                    return
                }
                val changes = mapOf<String, Any?>(
                    "owners/$uid/feederId" to id,
                    "feeders/$id/owner" to uid
                )
                db.updateChildren(changes)
                    .addOnSuccessListener { onDone(null) }
                    .addOnFailureListener { onDone("Could not pair: ${it.localizedMessage}") }
            }
            override fun onCancelled(e: DatabaseError) { onDone("Could not reach Firebase: ${e.message}") }
        })
    }

    /** onDone gets null if it worked, or a message for the owner. */
    fun unpair(id: String, onDone: (String?) -> Unit) {
        val changes = mapOf<String, Any?>(
            "owners/$uid/feederId" to null,
            "feeders/$id/owner" to null
        )
        db.updateChildren(changes)
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone("Could not unpair: ${it.localizedMessage}") }
    }
}