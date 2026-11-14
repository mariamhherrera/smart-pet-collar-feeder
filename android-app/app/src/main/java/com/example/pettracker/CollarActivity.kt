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
 * Location and safe zone (COL-6): where the collar is, and whether it is inside the safe zone
 * set in Settings. Sleep (COL-5) and the minutes per activity (COL-2.3) show "--" until the
 * collar sends its 1-minute summaries.
 */
class CollarActivity : Activity(), BluetoothSppClient.Listener {

    private val auth = FirebaseAuth.getInstance()
    private var cloud: PetCloudRepository? = null
    private var collarListener: ValueEventListener? = null
    private var collarClient: BluetoothSppClient? = null
    private var lastLocation: CollarReading? = null

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

        // Opens Google Maps (or any maps app) at the collar's last position. No API key needed.
        findViewById<Button>(R.id.buttonOpenMaps).setOnClickListener {
            val spot = lastLocation ?: return@setOnClickListener
            val place = "${spot.latitude},${spot.longitude}"
            val uri = android.net.Uri.parse("geo:$place?q=$place(Pet)")
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, "No maps app on this phone", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Bluetooth backup, for when the collar has no Wi-Fi
        findViewById<Button>(R.id.buttonSearchCollar).setOnClickListener {
            if (hasBluetoothPermissions()) startBluetoothBackup()
            else requestBluetoothPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        val user = auth.currentUser ?: return

        val cloud = PetCloudRepository(user.uid)
        this.cloud = cloud
        collarListener = cloud.listenToLatestCollar(
            onValue = { row -> runOnUiThread { CollarReading.fromMap(row)?.let { show(it) } } },
            onError = { message -> runOnUiThread { textStatus.text = "Firebase error: $message" } }
        )
        clockHandler.post(refreshStatus)
        showLocation()
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

        // Location and safe zone (COL-6)
        if (reading.hasLocation()) lastLocation = reading
        showLocation()

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

    /** "At home" / "Outside the safe zone" / "No GPS signal", with the distance and time. */
    private fun showLocation() {
        val status = findViewById<TextView>(R.id.textLocationStatus)
        val detail = findViewById<TextView>(R.id.textLocationDetail)
        val maps = findViewById<Button>(R.id.buttonOpenMaps)
        val spot = lastLocation
        maps.isEnabled = spot != null
        maps.alpha = if (spot != null) 1f else 0.5f
        if (spot == null) {
            status.text = "No GPS signal"
            status.setTextColor(getColor(R.color.text_primary))
            detail.text = "GPS works outside or near a window"
            return
        }

        val settings = SettingsStorage(this)
        val seen = "Last seen " + android.text.format.DateFormat.getTimeFormat(this).format(Date(spot.timeMillis))
        when (SafeZone.status(spot, settings.homeLatitude, settings.homeLongitude, settings.safeRadius)) {
            SafeZone.Status.INSIDE, SafeZone.Status.OUTSIDE -> {
                val meters = SafeZone.distanceMeters(settings.homeLatitude!!, settings.homeLongitude!!,
                    spot.latitude!!, spot.longitude!!)
                val inside = meters <= settings.safeRadius
                status.text = if (inside) "At home" else "Outside the safe zone"
                status.setTextColor(if (inside) 0xFF15803D.toInt() else 0xFFDC2626.toInt())
                detail.text = "$seen · ${SafeZone.formatDistance(meters)} from home"
            }
            else -> {
                status.text = "Location found"
                status.setTextColor(getColor(R.color.text_primary))
                detail.text = "$seen · Set home in Settings to use the safe zone"
            }
        }
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
            battery = null
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
