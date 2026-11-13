package com.example.pettracker

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.pettracker.bluetooth.BluetoothLink
import com.example.pettracker.bluetooth.ClassicBluetoothConnector
import com.example.pettracker.bluetooth.FeederMessage
import com.example.pettracker.bluetooth.FeederReply
import com.example.pettracker.bluetooth.LinkConfig
import com.example.pettracker.bluetooth.LinkListener
import com.example.pettracker.bluetooth.LinkState
import com.example.pettracker.feeder.ScheduleActivity
import com.example.pettracker.feeder.ScheduleStorage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class HomeActivity : Activity(), BluetoothSppClient.Listener {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseHelper
    private var collarClient: BluetoothSppClient? = null
    private lateinit var textConnection: TextView
    private lateinit var textActivity: TextView
    private lateinit var textHeartRate: TextView
    private lateinit var textCollarBattery: TextView
    private lateinit var searchButton: Button
    private var permissionRequest = 410
    private var cloud: PetCloudRepository? = null
    private var collarListener: ValueEventListener? = null
    private var feedingListener: ValueEventListener? = null

    // Feeder (FD-2.1): one Bluetooth link to PetFeeder_ESP32
    private var feederLink: BluetoothLink? = null
    private var feederConnected = false
    private var feederConnectionNumber = 0 // goes up for every new connection, to ignore messages from old ones
    private val feederSender = Executors.newSingleThreadExecutor() // sending can block, so not on the main thread
    private lateinit var textFeeder: TextView
    private lateinit var buttonFeedNow: Button
    private lateinit var textFeedHistory: TextView
    private lateinit var feedPortionGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        database = DatabaseHelper(this)

        if (auth.currentUser == null) {
            returnToLogin()
            return
        }

        setContentView(R.layout.activity_home)

        textConnection = findViewById(R.id.textConnection)
        textActivity = findViewById(R.id.textActivity)
        searchButton = findViewById(R.id.buttonSearchCollar)
        textHeartRate = findViewById(R.id.textHeartRate)
        textCollarBattery = findViewById(R.id.textCollarBattery)
        textFeeder = findViewById(R.id.textFeeder)
        textFeedHistory = findViewById(R.id.textFeedHistory)
        buttonFeedNow = findViewById(R.id.buttonFeedNow)
        feedPortionGroup = findViewById(R.id.homeFeedPortionGroup)
        buttonFeedNow.setOnClickListener { sendFeedNowToCloud() }

        val buttonProfile =
            findViewById<Button>(R.id.buttonProfile)

        val buttonLogout =
            findViewById<Button>(R.id.buttonLogout)

        buttonProfile.setOnClickListener {
            startActivity(
                Intent(this, ProfileActivity::class.java)
            )
        }
        findViewById<Button>(R.id.buttonCollar).setOnClickListener {
            startActivity(Intent(this, CollarActivity::class.java))
        }
        findViewById<Button>(R.id.buttonSchedule).setOnClickListener {
            startActivity(Intent(this, ScheduleActivity::class.java))
        }

        buttonLogout.setOnClickListener {
            // Logout from Firebase
            auth.signOut()
            detachCloudListeners()
            collarClient?.stop()
            collarClient = null
            stopFeeder()
            Toast.makeText(
                this,
                "Logged out successfully",
                Toast.LENGTH_SHORT
            ).show()

            returnToLogin()
        }

        searchButton.setOnClickListener {
            if (hasBluetoothPermissions()) {
                startBluetoothBackup()
                startFeeder()
            }
            else requestBluetoothPermissions()
        }
        showPetInformation()
    }

    override fun onResume() {
        super.onResume()

        if (!::auth.isInitialized) return

        if (auth.currentUser == null) {
            returnToLogin()
            return
        }

        showPetInformation()
        attachCloudListeners()
        if (hasBluetoothPermissions()) startFeeder()
    }

    override fun onPause() {
        super.onPause()
        detachCloudListeners()
        collarClient?.stop()
        collarClient = null
        stopFeeder()
    }

    override fun onDestroy() {
        super.onDestroy()
        feederSender.shutdown()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= 23){
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
    }
    
    private fun requestBluetoothPermissions() {
        val required = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else if (Build.VERSION.SDK_INT >= 23) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else emptyArray()
        if (required.isNotEmpty()) ActivityCompat.requestPermissions(this, required, permissionRequest)
        else startBluetoothBackup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (hasBluetoothPermissions()) {
                startBluetoothBackup()
                startFeeder()
            }
            else textConnection.text = "Firebase connected · Bluetooth backup permission denied"
        }
    }

    private fun startBluetoothBackup() {
        if (collarClient != null) return
        collarClient = BluetoothSppClient(this, this).also { it.start() }
    }

    // ---------- Feeder (FD-2.1) ----------

    /** Starts connecting to the feeder in the background. */
    private fun startFeeder() {
        if (feederLink != null || !hasBluetoothPermissions()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            showFeederStatus("Firebase feeder control ready · Bluetooth backup unavailable", false)
            return
        }
        val connector = ClassicBluetoothConnector(this, adapter, FeederMessage.DEVICE_NAME)
        feederConnectionNumber++
        // No silence timeout: the feeder only talks when it answers a command
        feederLink = BluetoothLink(connector, LinkConfig(), feederListener(feederConnectionNumber))
            .also { it.start() }
    }

    /** Disconnects from the feeder. A link can only be started once, so a new one is made next time. */
    private fun stopFeeder() {
        feederLink?.stop()
        feederLink = null
        feederConnected = false
        feederConnectionNumber++ // from now on, messages from the old connection are ignored
    }

    /** Called only when the Firebase Feed Now write fails: sends the Bluetooth backup. */
    private fun feedNow(portion: Int) {
        val link = feederLink ?: run {
            setFeedNowEnabled(cloud != null)
            showFeederStatus("Bluetooth backup is not connected", false)
            return
        }
        setFeedNowEnabled(false)              // one tap = one command, no double taps
        showFeederStatus("Firebase unavailable · sending Bluetooth backup…", true)

        feederSender.execute {
            val sent = link.send(FeederMessage.feedCommand(portion))
            runOnUiThread {
                if (!sent) showFeederStatus("Could not reach the feeder", false)
                // Same 3 seconds as the feeder's cooldown, then the button works again
                buttonFeedNow.postDelayed({ setFeedNowEnabled(cloud != null) }, FEED_LOCK_MS)
            }
        }
    }

    // BluetoothLink calls these on its own thread, so every screen change goes through runOnUiThread.
    // [number] is the connection this listener belongs to: messages from an older connection are ignored.
    private fun feederListener(number: Int) = object : LinkListener {
        override fun onState(state: LinkState, deviceName: String?) {
            runOnUiThread {
                if (number != feederConnectionNumber) return@runOnUiThread
                feederConnected = state == LinkState.CONNECTED
                setFeedNowEnabled(cloud != null)
                when (state) {
                    LinkState.CONNECTED -> showFeederStatus("Feeder connected", true)
                    LinkState.SEARCHING -> showFeederStatus("Searching for the feeder…", false)
                    LinkState.CONNECTING -> showFeederStatus("Connecting to the feeder…", false)
                    LinkState.RECONNECTING -> showFeederStatus("Feeder lost. Reconnecting…", false)
                    LinkState.STOPPED -> showFeederStatus("Feeder disconnected", false)
                }
            }
        }

        override fun onLine(line: String) {
            runOnUiThread {
                if (number != feederConnectionNumber) return@runOnUiThread
                // Turn the feeder's reply (like "OK FED 1") into a sentence for the owner
                val reply = FeederMessage.parse(line) ?: return@runOnUiThread // not a reply, ignore it
                // Answers to the automatic TIME and SCHEDULE: keep showing "Feeder connected"
                if (reply is FeederReply.TimeSet || reply is FeederReply.ScheduleSaved) return@runOnUiThread
                val good = reply is FeederReply.Fed || reply is FeederReply.ScheduledFed
                showFeederStatus(FeederMessage.screenText(reply), good)
            }
        }
    }

    /**
     * FD-1.3: Gives the feeder the current time and the saved schedule, every time it connects.
     * The feeder forgets the time when it restarts, and needs it to feed on schedule.
     */
    private fun sendTimeAndSchedule() {
        val link = feederLink ?: return
        val storage = ScheduleStorage(this)
        val schedule =
            if (storage.hasSchedule())
                FeederMessage.scheduleCommand(storage.getHour(), storage.getMinute(), storage.getPortion())
            else null
        feederSender.execute {
            link.send(FeederMessage.timeCommandNow())
            if (schedule != null) link.send(schedule)
        }
    }

    /** Turns Feed Now on or off. The teal button looks the same either way, so it is faded when off. */
    private fun setFeedNowEnabled(enabled: Boolean) {
        buttonFeedNow.isEnabled = enabled
        buttonFeedNow.alpha = if (enabled) 1f else 0.5f
    }

    private fun showFeederStatus(message: String, good: Boolean) {
        textFeeder.text = message
        textFeeder.setTextColor(if (good) 0xFF15803D.toInt() else 0xFFDC2626.toInt())
    }

    private companion object {
        const val FEED_LOCK_MS = 3_000L
    }

    private fun showPetInformation() {
        val user = auth.currentUser ?: return
        val profile = database.getPetProfile(user.uid)

        findViewById<TextView>(R.id.textWelcome).text =
            "Welcome, ${user.email ?: "User"}"

        findViewById<TextView>(R.id.textPetName).text =
            profile?.name ?: "No pet profile yet"

        findViewById<TextView>(R.id.textConnection).text =
            "Disconnected"

        findViewById<TextView>(R.id.textActivity).text =
            "No data yet"
    }

    override fun onStatus(message: String, connected: Boolean) {
         runOnUiThread {
            textConnection.text = if (connected) "Bluetooth backup connected" else "Firebase primary · $message"
            textConnection.setTextColor(if (connected) 0xFF15803D.toInt() else 0xFF64748B.toInt())
        }
    }

    override fun onLineReceived(line: String) {
        val motion = CollarProtocol.parseMotion(line) ?: return
        val uid = auth.currentUser?.uid ?: return
        val record = mapOf(
            "time" to System.currentTimeMillis(),
            "receivedAt" to System.currentTimeMillis(),
            "deviceTimeSeconds" to motion.timeSeconds,
            "accelerationG" to mapOf("x" to motion.ax, "y" to motion.ay, "z" to motion.az),
            "gyroscopeDegreesPerSecond" to mapOf("x" to motion.gx, "y" to motion.gy, "z" to motion.gz),
            "heartRateBpm" to motion.heartRateBpm,
            "source" to "bluetooth_backup"
        )
        PetCloudRepository(uid).collarLatestRef().updateChildren(record) // UPDATED: preserve ESP32 battery fields during Bluetooth fallback updates.
    }

    private fun attachCloudListeners() {
        if (collarListener != null || feedingListener != null) return
        val uid = auth.currentUser?.uid ?: return
        cloud = PetCloudRepository(uid)
        setFeedNowEnabled(true)
        textConnection.text = "Connected through Firebase"
        textConnection.setTextColor(0xFF15803D.toInt())

        collarListener = cloud?.listenToLatestCollar(
            onValue = { row ->
                runOnUiThread {
                    if (row == null) {
                        textActivity.text = "Waiting for collar readings in Firebase…"
                    } else {
                        val acceleration = row["accelerationG"] as? Map<*, *>
                        val gyroscope = row["gyroscopeDegreesPerSecond"] as? Map<*, *>
                        val ax = acceleration?.get("x")?.toString() ?: row["ax"]?.toString() ?: row["accelX"]?.toString() ?: "—"
                        val ay = acceleration?.get("y")?.toString() ?: row["ay"]?.toString() ?: row["accelY"]?.toString() ?: "—"
                        val az = acceleration?.get("z")?.toString() ?: row["az"]?.toString() ?: row["accelZ"]?.toString() ?: "—"
                        val gx = gyroscope?.get("x")?.toString() ?: row["gx"]?.toString() ?: "—"
                        val gy = gyroscope?.get("y")?.toString() ?: row["gy"]?.toString() ?: "—"
                        val gz = gyroscope?.get("z")?.toString() ?: row["gz"]?.toString() ?: "—"
                        val heartRate = row["heartRateBpm"]?.toString()
                            ?: row["heartRate"]?.toString() ?: "—"
                        textHeartRate.text = if (heartRate == "—") "Heart rate: waiting for collar data" else "Heart rate: $heartRate BPM"
                        val batteryPercent = row["batteryPercent"]?.toString()
                        textCollarBattery.text = batteryPercent?.let { "Collar battery: $it%" } ?: "Collar battery: unavailable"
                        val timeValue = (row["receivedAt"] as? Number)?.toLong()
                            ?: (row["time"] as? Number)?.toLong()
                            ?: (row["timestamp"] as? Number)?.toLong()
                        val time = timeValue?.takeIf { it > 0L }?.let {
                            DateFormat.getDateTimeInstance().format(Date(it))
                        } ?: row["deviceTimeSeconds"]?.toString() ?: "—"
                        textActivity.text = "LIVE COLLAR DATA\nTime: $time\nAcceleration (g): X $ax | Y $ay | Z $az\nGyroscope (°/s): X $gx | Y $gy | Z $gz"
                    }
                }
            },
            onError = { message -> runOnUiThread { textConnection.text = "Firebase collar error: $message" } }
        )

        feedingListener = cloud?.listenToFeedings(
            onValue = { rows ->
                runOnUiThread {
                    textFeedHistory.text = if (rows.isEmpty()) "No meals recorded yet" else rows.joinToString("\n\n") { row ->
                        val timeValue = (row["time"] as? Number)?.toLong()
                            ?: (row["time"] as? String)?.toLongOrNull() ?: 0L
                        val time = if (timeValue > 0) DateFormat.getDateTimeInstance().format(Date(timeValue)) else "Time pending"
                        "Time: $time\nPortion: ${row["portion"] ?: "—"}\nStatus: ${row["status"] ?: "unknown"}\nSource: ${row["source"] ?: "device"}"
                    }
                }
            },
            onError = { message -> runOnUiThread { textFeeder.text = "Firebase feed history error: $message" } }
        )
    }

    private fun detachCloudListeners() {
        collarListener?.let { cloud?.removeCollarListener(it) }
        feedingListener?.let { cloud?.removeFeedingsListener(it) }
        collarListener = null
        feedingListener = null
        cloud = null
    }

    private fun sendFeedNowToCloud() {
        val portion = when (feedPortionGroup.checkedRadioButtonId) {
            R.id.homeFeedSmall -> 1
            R.id.homeFeedLarge -> 3
            else -> 2
        }
        buttonFeedNow.isEnabled = false
        textFeeder.text = "Sending Feed Now command to Firebase…"
        cloud?.createFeedNow(portion) { commandId, error ->
            runOnUiThread {
                if (error != null) {
                    cloud?.addAlert("command_error", error)
                    if (feederConnected) {
                        feedNow(portion)
                    } else {
                        setFeedNowEnabled(true)
                        textFeeder.text = "Could not save Feed Now command: $error. Bluetooth backup is unavailable."
                    }
                } else {
                    setFeedNowEnabled(true)
                    textFeeder.text = "Feed Now queued in Firebase (command ${commandId?.takeLast(6)}). Waiting for feeder confirmation."
                }
            }
        } ?: run {
            if (feederConnected) feedNow(portion) else {
                setFeedNowEnabled(true)
                textFeeder.text = "Firebase is not connected and Bluetooth backup is unavailable."
            }
        }
    }

    private fun returnToLogin() {
        val intent = Intent(
            this,
            MainActivity::class.java
        )

        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK

        startActivity(intent)
        finish()
    }
}
