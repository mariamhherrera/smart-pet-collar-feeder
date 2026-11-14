package com.example.pettracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import java.util.Date

/**
 * UI-5 Collar vitals page.
 * Main way: the collar sends to Firebase over Wi-Fi (COM-3.2), this page listens to collar/latest.
 * Backup: "Connect by Bluetooth" reads the collar directly and writes the readings to Firebase,
 * so Home and History see them the same way.
 * Heart rate is coloured with the normal range for the pet's species (UI-5.1, UI-5.3).
 * The minutes per activity show "--" until COL-2.3.
 */
class CollarActivity : Activity(), BluetoothSppClient.Listener {

    private val auth = FirebaseAuth.getInstance()
    private var cloud: PetCloudRepository? = null
    private var collarListener: ValueEventListener? = null
    private var collarClient: BluetoothSppClient? = null
    private var petSpecies: String? = null

    private lateinit var textStatus: TextView
    private lateinit var textMotion: TextView

    // The collar sends every 5 seconds, so 30 seconds of silence = offline
    private var lastReadingTime = 0L
    private val clockHandler = Handler(Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() {
            showOnline()
            clockHandler.postDelayed(this, 10_000L)
        }
    }

    private val permissionRequest = 411

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collar)
        BottomNav.setUp(this, BottomNav.COLLAR)

        textStatus = findViewById(R.id.textCollarStatus)
        textMotion = findViewById(R.id.textCollarMotion)

        // Bluetooth backup, for when the collar has no Wi-Fi
        findViewById<Button>(R.id.buttonSearchCollar).setOnClickListener {
            if (hasBluetoothPermissions()) startBluetoothBackup()
            else requestBluetoothPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        val user = auth.currentUser ?: return
        petSpecies = DatabaseHelper(this).getPetProfile(user.uid)?.species
        findViewById<TextView>(R.id.textHeartRateRange).text = HeartRateRange.rangeText(petSpecies)

        val cloud = PetCloudRepository(user.uid)
        this.cloud = cloud
        collarListener = cloud.listenToLatestCollar(
            onValue = { row -> runOnUiThread { CollarReading.fromMap(row)?.let { show(it) } } },
            onError = { message -> runOnUiThread { textStatus.text = "Firebase error: $message" } }
        )
        clockHandler.post(refreshStatus)
    }

    override fun onPause() {
        super.onPause()
        collarListener?.let { cloud?.removeCollarListener(it) }
        collarListener = null
        cloud = null
        clockHandler.removeCallbacks(refreshStatus)
        // Only one page can talk to the collar over Bluetooth at a time, so let go when leaving
        collarClient?.stop()
        collarClient = null
    }

    /** Puts one reading on the page. */
    private fun show(reading: CollarReading) {
        lastReadingTime = if (reading.timeMillis > 0) reading.timeMillis else System.currentTimeMillis()
        showOnline()
        cloud?.saveReadingToHistory(reading) // UI-6.1

        // Heart rate, coloured low / normal / high
        val number = findViewById<TextView>(R.id.textCollarHeartRate)
        val level = findViewById<TextView>(R.id.textHeartRateLevel)
        val bpm = reading.heartRate
        if (bpm != null && bpm > 0) {
            val result = HeartRateRange.levelOf(bpm, petSpecies)
            number.text = bpm.toString()
            number.setTextColor(result.color)
            level.text = result.label
            level.setTextColor(result.color)
        } else {
            number.text = "--"
            number.setTextColor(getColor(R.color.text_primary))
            level.text = ""
        }

        // Battery, once the collar sends it
        findViewById<TextView>(R.id.textCollarBattery).text =
            reading.battery?.let { "$it%" } ?: "--%"

        // Live motion: how much the collar moves (0 g = still), and the raw numbers
        findViewById<TextView>(R.id.textCollarActivity).text = "%.2f g".format(reading.movement())
        textMotion.text = "X %.2f · Y %.2f · Z %.2f g · %s".format(
            reading.ax, reading.ay, reading.az,
            android.text.format.DateFormat.getTimeFormat(this).format(Date(lastReadingTime))
        )
    }

    private fun showOnline() {
        if (lastReadingTime == 0L) {
            textStatus.text = "Waiting for the collar"
            textStatus.setTextColor(getColor(R.color.text_secondary))
            return
        }
        val online = System.currentTimeMillis() - lastReadingTime < 30_000L
        textStatus.text = if (online) "Online" else "Offline since " +
            android.text.format.DateFormat.getTimeFormat(this).format(Date(lastReadingTime))
        textStatus.setTextColor(if (online) 0xFF15803D.toInt() else 0xFFDC2626.toInt())
    }

    // ---------- Bluetooth backup ----------

    private fun startBluetoothBackup() {
        if (collarClient != null) return
        collarClient = BluetoothSppClient(this, this).also { it.start() }
    }

    override fun onStatus(message: String, connected: Boolean) {
        val button = findViewById<Button>(R.id.buttonSearchCollar)
        button.text = if (connected) "Bluetooth backup connected" else "Bluetooth: $message"
    }

    override fun onLineReceived(line: String) {
        val motion = CollarProtocol.parseMotion(line) ?: return
        val reading = CollarReading(
            System.currentTimeMillis(),
            motion.ax, motion.ay, motion.az, motion.gx, motion.gy, motion.gz,
            heartRate = motion.heartRateBpm, battery = null
        )
        // Through Firebase, so every page sees it the same way. The page updates from the listener.
        cloud?.writeLatestFromBluetooth(reading)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val required = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, required, permissionRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && hasBluetoothPermissions()) startBluetoothBackup()
    }
}
