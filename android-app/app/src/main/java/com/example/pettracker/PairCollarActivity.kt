package com.example.pettracker

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import java.util.Date

/**
 * COM-4.2 Pair your collar.
 *
 * Opened right after the pet profile is saved (step 2 of 2), or from the pet profile
 * ("Pair collar" / "Change collar").
 *
 *  1. Looking for the collar: listens for collars that sent data in the last minute
 *     (collars/<ID>/seenAt). The owner can also type the ID from the sticker.
 *  2. Collar found: shows the ID, online, battery and signal.
 *     "Link to Max" saves the link, "Not my collar" goes back to looking.
 *
 * Errors (COM-4.3): wrong ID, collar never connected, linked to another account,
 * or Firebase failed. Every error lets the owner try again.
 */
class PairCollarActivity : Activity() {

    companion object {
        /** True right after registering: shows "Step 2 of 2". */
        const val EXTRA_SETUP = "setup"

        /** If nothing is found after 45 seconds, give a hint. */
        const val HINT_AFTER_MS = 45_000L
    }

    private val auth = FirebaseAuth.getInstance()
    private lateinit var link: CollarLink
    private lateinit var uid: String
    private var petName = "your pet"

    private var foundId: String? = null                 // null = still looking
    private val skipped = mutableSetOf<String>()        // collars the owner said "Not my collar" to
    private var seenCollars = listOf<CollarPairing.SeenCollar>()
    private var search: Pair<Query, ValueEventListener>? = null
    private var foundWatch: Pair<Query, ValueEventListener>? = null
    private var busy = false                            // waiting for Firebase

    private val handler = Handler(Looper.getMainLooper())
    private val showHint = Runnable {
        if (foundId == null) {
            findViewById<TextView>(R.id.textSearching).text =
                "Still looking… Check that the collar is on and near your Wi-Fi, or type its ID below."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = auth.currentUser ?: run { finish(); return }
        uid = user.uid

        setContentView(R.layout.activity_pair_collar)
        BottomNav.setUpDetail(this, "Pair your collar")

        val setup = intent.getBooleanExtra(EXTRA_SETUP, false)
        findViewById<View>(R.id.textStep).visibility = if (setup) View.VISIBLE else View.GONE

        link = CollarLink(this, uid)
        DatabaseHelper(this).getPetProfile(uid)?.name?.let { petName = it }

        // "Change collar": the collar that is linked now should not be "found" again
        link.collarId?.let { skipped.add(it) }

        findViewById<Button>(R.id.buttonPairMain).setOnClickListener {
            if (busy) return@setOnClickListener
            if (foundId == null) checkTypedId() else linkFoundCollar()
        }
        findViewById<Button>(R.id.buttonPairSecond).setOnClickListener {
            if (busy) return@setOnClickListener
            if (foundId == null) finish()   // Skip for now / Cancel
            else notMyCollar()
        }

        showSearching()
    }

    override fun onResume() {
        super.onResume()
        search = link.listenForCollars(
            onValue = { collars ->
                runOnUiThread {
                    seenCollars = collars
                    if (foundId == null && !busy) showNewestCollar()
                }
            },
            onError = { message -> runOnUiThread { showError("Firebase error: $message") } }
        )
        foundId?.let { watchFoundCollar(it) }
        handler.postDelayed(showHint, HINT_AFTER_MS)
    }

    override fun onPause() {
        super.onPause()
        search?.let { (query, listener) -> query.removeEventListener(listener) }
        search = null
        stopWatchingFoundCollar()
        handler.removeCallbacks(showHint)
    }

    // ---------- 1. Looking for the collar ----------

    private fun showSearching() {
        foundId = null
        stopWatchingFoundCollar()
        findViewById<View>(R.id.layoutSearch).visibility = View.VISIBLE
        findViewById<View>(R.id.layoutFound).visibility = View.GONE
        findViewById<TextView>(R.id.textSearching).text = "Looking for your collar…"
        findViewById<Button>(R.id.buttonPairMain).text = "Link collar"
        val setup = intent.getBooleanExtra(EXTRA_SETUP, false)
        findViewById<Button>(R.id.buttonPairSecond).text = if (setup) "Skip for now" else "Cancel"
        hideError()
    }

    /** Shows the newest collar that is online, not someone else's, and not skipped. */
    private fun showNewestCollar() {
        val collar = CollarPairing.pickFound(seenCollars, uid, skipped, System.currentTimeMillis()) ?: return
        showFound(collar.id)
    }

    /** "Link collar" with a typed ID: check it, then show it as found. */
    private fun checkTypedId() {
        val typed = findViewById<EditText>(R.id.editCollarId).text.toString()
        if (typed.isBlank()) {
            showError("Wait for the collar to be found, or type its ID (like PC-3C71F2).")
            return
        }
        val id = CollarPairing.normalizeId(typed) ?: run {
            showError("Collar IDs look like PC-3C71F2: PC- and then 6 letters or numbers.")
            return
        }

        setBusy(true)
        link.loadCollar(id,
            onValue = { collar, _ ->
                runOnUiThread {
                    setBusy(false)
                    when {
                        collar == null -> showError("No collar with the ID $id has connected yet. " +
                            "Turn it on, make sure it is on Wi-Fi, and try again.")
                        collar.owner != null && collar.owner != uid ->
                            showError("This collar is linked to another account.")
                        else -> showFound(id)
                    }
                }
            },
            onError = { message -> runOnUiThread { setBusy(false); showError("Firebase error: $message") } }
        )
    }

    // ---------- 2. Collar found ----------

    private fun showFound(id: String) {
        foundId = id
        findViewById<View>(R.id.layoutSearch).visibility = View.GONE
        findViewById<View>(R.id.layoutFound).visibility = View.VISIBLE
        findViewById<TextView>(R.id.textFoundId).text = id
        findViewById<TextView>(R.id.textFoundQuestion).text = "Link this collar to $petName?"
        findViewById<TextView>(R.id.textFoundNote).text = "Readings from other collars will not show for $petName."
        findViewById<Button>(R.id.buttonPairMain).text = "Link to $petName"
        findViewById<Button>(R.id.buttonPairSecond).text = "Not my collar"
        hideError()
        showFoundDetails(null)
        watchFoundCollar(id)
    }

    /** Keeps online, battery and signal up to date while the page is open. */
    private fun watchFoundCollar(id: String) {
        stopWatchingFoundCollar()
        foundWatch = link.listenToCollar(id) { reading -> runOnUiThread { showFoundDetails(reading) } }
    }

    private fun stopWatchingFoundCollar() {
        foundWatch?.let { (query, listener) -> query.removeEventListener(listener) }
        foundWatch = null
    }

    private fun showFoundDetails(reading: CollarReading?) {
        val status = findViewById<TextView>(R.id.textFoundStatus)
        val now = System.currentTimeMillis()
        val time = reading?.timeMillis ?: 0L

        if (CollarPairing.isOnline(time, now)) {
            val seconds = ((now - time) / 1000).coerceAtLeast(0)
            status.text = if (seconds < 10) "Online · sent data just now" else "Online · sent data $seconds s ago"
            status.setTextColor(getColor(R.color.primary))
        } else {
            status.text = if (time > 0) "Offline · last seen " +
                android.text.format.DateFormat.getTimeFormat(this).format(Date(time))
            else "Waiting for data from the collar"
            status.setTextColor(getColor(R.color.text_secondary))
        }

        findViewById<TextView>(R.id.textFoundBattery).text = reading?.battery?.let { "$it%" } ?: "--"
        findViewById<TextView>(R.id.textFoundSignal).text = CollarPairing.signalName(reading?.wifiSignal)
    }

    private fun linkFoundCollar() {
        val id = foundId ?: return
        setBusy(true)
        link.link(id) { error ->
            runOnUiThread {
                setBusy(false)
                if (error != null) {
                    showError("$error Tap \"Link to $petName\" to try again.")
                } else {
                    Toast.makeText(this, "Collar $id linked to $petName", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun notMyCollar() {
        foundId?.let { skipped.add(it) }
        showSearching()
        showNewestCollar()   // maybe another collar is waiting
    }

    // ---------- Small helpers ----------

    private fun setBusy(isBusy: Boolean) {
        busy = isBusy
        listOf(R.id.buttonPairMain, R.id.buttonPairSecond).forEach {
            findViewById<Button>(it).apply { isEnabled = !isBusy; alpha = if (isBusy) 0.5f else 1f }
        }
    }

    private fun showError(message: String) {
        findViewById<TextView>(R.id.textPairError).apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    private fun hideError() {
        findViewById<View>(R.id.textPairError).visibility = View.GONE
    }
}
