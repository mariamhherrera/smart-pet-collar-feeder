package com.example.pettracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
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
 * Main way: the collar sends to Firebase over Wi-Fi (COM-3.2), this page listens to
 * collars/<ID>/latest of the collar linked to the pet (COM-4).
 * Backup: "Connect by Bluetooth" reads the collar directly and writes the readings to Firebase,
 * so Home and History see them the same way.
 * Activity (COL-2.2): resting / walking / running, worked out on the collar every 4 seconds.
 * Today (COL-2.3): minutes of each activity since midnight, counted on the collar.
 * Location and safe zone (COL-6): where the collar is, and whether it is inside the safe zone
 * set in Settings.
 */
class CollarActivity : Activity(), BluetoothSppClient.Listener {

    private val auth = FirebaseAuth.getInstance()
    private var cloud: PetCloudRepository? = null
    private var collarListener: ValueEventListener? = null
    private var collarId: String? = null      // the collar linked to the pet (COM-4)
    private var resumed = false
    private var shownActivity: String? = null  // to "pop" the symbol only when it changes
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
        resumed = true

        // The phone's copy of the link right away, then check Firebase in case it changed
        val link = CollarLink(this, user.uid)
        listenToCollar(user.uid, link.collarId)
        link.refresh { id ->
            runOnUiThread { if (resumed && id != collarId) listenToCollar(user.uid, id) }
        }
        clockHandler.post(refreshStatus)
        showLocation()
    }

    /** Starts listening to the linked collar (or shows "No collar linked"). */
    private fun listenToCollar(uid: String, id: String?) {
        stopListening()
        collarId = id
        lastReadingTime = 0L
        val cloud = PetCloudRepository(uid, id)
        this.cloud = cloud
        if (id == null) {
            showNoCollar()
            return
        }
        collarListener = cloud.listenToLatestCollar(
            onValue = { row -> runOnUiThread { CollarReading.fromMap(row)?.let { show(it) } } },
            onError = { message -> runOnUiThread { textStatus.text = "Firebase error: $message" } }
        )
    }

    private fun stopListening() {
        collarListener?.let { cloud?.removeCollarListener(it) }
        collarListener = null
        cloud = null
    }

    private fun showNoCollar() {
        textStatus.text = "No collar linked. Pair it in the pet profile."
        textStatus.setTextColor(getColor(R.color.text_secondary))
        findViewById<TextView>(R.id.textCollarActivity).text = "No collar linked"
        showActivitySymbol(null)
        textMotion.text = ""
        for (id in listOf(R.id.textMinutesResting, R.id.textMinutesWalking, R.id.textMinutesRunning)) {
            findViewById<TextView>(id).text = "-- min"
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        stopListening()
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

        // COL-2.2: resting / walking / running ("Measuring…" for the first 4 seconds)
        findViewById<TextView>(R.id.textCollarActivity).text = reading.activityLabel() ?: "Measuring…"
        showActivitySymbol(reading.activity)
        textMotion.text = "Updated " +
            android.text.format.DateFormat.getTimeFormat(this).format(Date(lastReadingTime))

        // COL-2.3: today's minutes of each activity, counted on the collar
        val seconds = reading.secondsToday(HistoryRange.dayKey(System.currentTimeMillis()))
        findViewById<TextView>(R.id.textMinutesResting).text = CollarPairing.formatMinutes(seconds[0])
        findViewById<TextView>(R.id.textMinutesWalking).text = CollarPairing.formatMinutes(seconds[1])
        findViewById<TextView>(R.id.textMinutesRunning).text = CollarPairing.formatMinutes(seconds[2])
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
        if (collarId == null) return   // "No collar linked" stays
        if (lastReadingTime == 0L) {
            textStatus.text = "Waiting for the collar"
            textStatus.setTextColor(getColor(R.color.text_secondary))
            return
        }
        val online = CollarPairing.isOnline(lastReadingTime, System.currentTimeMillis())
        textStatus.text = if (online) "Online · $collarId" else "Offline since " +
            android.text.format.DateFormat.getTimeFormat(this).format(Date(lastReadingTime)) +
            " · last activity shown"
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

    /**
     * The symbol beside the activity word: a dog lying down (resting), walking, or running.
     * Same colours as the dots in the Today list. It "pops" when the activity changes.
     * Hidden when there is no activity yet (or no collar).
     */
    private fun showActivitySymbol(activity: String?) {
        val symbol = findViewById<ImageView>(R.id.imageCollarActivity)
        val style = when (activity) {
            "resting" -> Triple(R.drawable.ic_dog_resting, R.color.resting, R.color.resting_light)
            "walking" -> Triple(R.drawable.ic_dog_walking, R.color.primary, R.color.primary_light)
            "running" -> Triple(R.drawable.ic_dog_running, R.color.accent, R.color.accent_light)
            else -> null
        }
        if (style == null) {
            symbol.visibility = View.GONE
            shownActivity = null
            return
        }

        val (image, color, lightColor) = style
        symbol.setImageResource(image)
        symbol.imageTintList = ColorStateList.valueOf(getColor(color))
        symbol.backgroundTintList = ColorStateList.valueOf(getColor(lightColor))
        symbol.visibility = View.VISIBLE

        if (activity != shownActivity) {
            // Small "pop" so the owner notices the change
            symbol.scaleX = 0.6f
            symbol.scaleY = 0.6f
            symbol.animate().scaleX(1f).scaleY(1f).setDuration(350)
                .setInterpolator(OvershootInterpolator(3f)).start()
        }
        shownActivity = activity
    }
}
