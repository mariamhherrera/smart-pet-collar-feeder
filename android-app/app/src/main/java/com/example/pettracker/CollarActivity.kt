package com.example.pettracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import java.text.DateFormat
import java.util.Date

/**
 * UI-5: Collar page.
 * Shows the pet's live heart rate and activity from Firebase (collar/latest),
 * colours the heart rate green or red, and says when the collar is disconnected.
 */
class CollarActivity : Activity() {

    private lateinit var textStatus: TextView
    private lateinit var textBpm: TextView
    private lateinit var textBpmVerdict: TextView
    private lateinit var textNormalRange: TextView
    private lateinit var textActivity: TextView
    private lateinit var textLastUpdate: TextView
    private lateinit var textPetName: TextView

    private var cloud: PetCloudRepository? = null
    private var collarListener: ValueEventListener? = null

    private var range: CollarVitals.Range? = null   // normal heart rate for this pet
    private var latest: Map<String, Any>? = null     // newest reading from Firebase

    // Redraws the screen every 5 seconds, so "disconnected" appears
    // even when Firebase stops sending new readings.
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            showVitals()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FirebaseAuth.getInstance().currentUser == null) {
            goToLogin()
            return
        }

        setContentView(R.layout.activity_collar)

        textStatus = findViewById(R.id.textCollarStatus)
        textBpm = findViewById(R.id.textBpm)
        textBpmVerdict = findViewById(R.id.textBpmVerdict)
        textNormalRange = findViewById(R.id.textNormalRange)
        textActivity = findViewById(R.id.textCollarActivity)
        textLastUpdate = findViewById(R.id.textLastUpdate)
        textPetName = findViewById(R.id.textCollarPetName)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val user = FirebaseAuth.getInstance().currentUser ?: return

        // 1. The pet's species decides the normal heart rate range (UI-5.1)
        val profile = DatabaseHelper(this).getPetProfile(user.uid)
        range = CollarVitals.normalRange(profile?.species)
        if (profile != null) textPetName.text = "${profile.name}'s heart rate and activity"
        textNormalRange.text = range?.let { "Normal range for a ${profile?.species?.lowercase()}: ${it.low}-${it.high} BPM" }
            ?: "Normal range: unknown. Add a pet profile to see it."

        // 2. Listen to the newest collar reading in Firebase
        cloud = PetCloudRepository(user.uid)
        collarListener = cloud?.listenToLatestCollar(
            onValue = { row ->
                runOnUiThread {
                    latest = row
                    showVitals()
                }
            },
            onError = { message ->
                runOnUiThread { showStatus("Firebase error: $message", RED) }
            }
        )

        // 3. Start redrawing every 5 seconds
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        collarListener?.let { cloud?.removeCollarListener(it) }
        collarListener = null
        cloud = null
        handler.removeCallbacks(refresh)
    }

    /** Puts the newest reading on the screen. */
    private fun showVitals() {
        val row = latest
        val readingTime = (row?.get("receivedAt") as? Number)?.toLong()
        val connected = CollarVitals.isConnected(readingTime, System.currentTimeMillis())

        textLastUpdate.text = if (readingTime == null) "Last reading: none yet"
            else "Last reading: " + DateFormat.getDateTimeInstance().format(Date(readingTime))

        // Collar off, out of battery or no Wi-Fi: don't show old values as if they were live
        if (!connected) {
            showStatus(if (row == null) "Waiting for the collar…" else "Collar disconnected", RED)
            textBpm.text = "—"
            textBpm.setTextColor(GREY)
            textBpmVerdict.text = "No live data. Check that the collar is on and charged."
            textBpmVerdict.setTextColor(GREY)
            textActivity.text = "—"
            return
        }

        showStatus("Collar connected", GREEN)
        showHeartRate((row?.get("heartRateBpm") as? Number)?.toInt())
        showActivity(row)
    }

    /** UI-5.3: green if the heart rate is in the normal range, red if not. */
    private fun showHeartRate(bpm: Int?) {
        if (bpm == null) {
            textBpm.text = "—"
            textBpm.setTextColor(GREY)
            textBpmVerdict.text = "Waiting for heart rate"
            textBpmVerdict.setTextColor(GREY)
            return
        }

        textBpm.text = "$bpm BPM"
        val normalRange = range
        if (normalRange == null) {
            // No pet profile, so we can't say if it's normal
            textBpm.setTextColor(BLACK)
            textBpmVerdict.text = "Add a pet profile to check if this is normal"
            textBpmVerdict.setTextColor(GREY)
        } else if (CollarVitals.isNormal(bpm, normalRange)) {
            textBpm.setTextColor(GREEN)
            textBpmVerdict.text = "Normal"
            textBpmVerdict.setTextColor(GREEN)
        } else {
            textBpm.setTextColor(RED)
            textBpmVerdict.text = if (bpm < normalRange.low) "Lower than normal" else "Higher than normal"
            textBpmVerdict.setTextColor(RED)
        }
    }

    /** Uses COL-2's activity if it's in Firebase, otherwise a rough Still / Moving. */
    private fun showActivity(row: Map<String, Any>?) {
        val fromCol2 = row?.get("activity") as? String
        if (fromCol2 != null) {
            textActivity.text = fromCol2.replaceFirstChar { it.uppercase() }
            return
        }

        val gyro = row?.get("gyroscopeDegreesPerSecond") as? Map<*, *>
        val gx = (gyro?.get("x") as? Number)?.toDouble()
        val gy = (gyro?.get("y") as? Number)?.toDouble()
        val gz = (gyro?.get("z") as? Number)?.toDouble()
        textActivity.text = if (gx == null || gy == null || gz == null) "—"
            else CollarVitals.roughActivity(gx, gy, gz)
    }

    private fun showStatus(message: String, colour: Int) {
        textStatus.text = message
        textStatus.setTextColor(colour)
    }

    private fun goToLogin() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private companion object {
        const val REFRESH_MS = 5_000L
        // Same green and red as the rest of the app
        const val GREEN = 0xFF15803D.toInt()
        const val RED = 0xFFDC2626.toInt()
        const val GREY = 0xFF475569.toInt()
        const val BLACK = 0xFF0F172A.toInt()
    }
}
