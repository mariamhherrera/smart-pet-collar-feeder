package com.example.pettracker

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import androidx.core.app.ActivityCompat
import com.example.pettracker.bluetooth.BluetoothLink
import com.example.pettracker.bluetooth.ClassicBluetoothConnector
import com.example.pettracker.bluetooth.LinkConfig
import com.example.pettracker.bluetooth.LinkListener
import com.example.pettracker.bluetooth.LinkState
import java.util.concurrent.Executors

class HomeActivity : Activity(), BluetoothSppClient.Listener {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseHelper
    private var collarClient: BluetoothSppClient? = null
    private lateinit var textConnection: TextView
    private lateinit var textActivity: TextView
    private lateinit var searchButton: Button
    private var permissionRequest = 410

    // Feeder (FD-2.1): one Bluetooth link to PetFeeder_ESP32
    private var feederLink: BluetoothLink? = null
    private var feederConnected = false
    private var feederConnectionNumber = 0 // goes up for every new connection, to ignore messages from old ones
    private val feederSender = Executors.newSingleThreadExecutor() // sending can block, so not on the main thread
    private lateinit var textFeeder: TextView
    private lateinit var buttonFeedNow: Button

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
        textFeeder = findViewById(R.id.textFeeder)
        buttonFeedNow = findViewById(R.id.buttonFeedNow)
        buttonFeedNow.setOnClickListener { feedNow() }

        val buttonProfile =
            findViewById<Button>(R.id.buttonProfile)

        val buttonLogout =
            findViewById<Button>(R.id.buttonLogout)

        buttonProfile.setOnClickListener {

            startActivity(
                Intent(this, ProfileActivity::class.java)
            )
        }
        findViewById<Button>(R.id.buttonSchedule).setOnClickListener {
            startActivity(Intent(this, com.example.pettracker.feeder.ScheduleActivity::class.java))
        }

        buttonLogout.setOnClickListener {

            // Logout from Firebase
            auth.signOut()
            collarClient?.stop()
            stopFeeder()
            Toast.makeText(
                this,
                "Logged out successfully",
                Toast.LENGTH_SHORT
            ).show()

            returnToLogin()
        }

        searchButton.setOnClickListener {
            if(hasBluetoothPermissions()) collarClient?.searchNow()
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
        if (hasBluetoothPermissions()) startBluetooth()
        else requestBluetoothPermissions()
    }

    override fun onPause() {
        super.onPause()
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
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (hasBluetoothPermissions()) startBluetooth()
            else onStatus("Bluetooth permissions denied", false)
        }
    }

    private fun startBluetooth() {
        startFeeder()
        if (collarClient!= null) return
        collarClient= BluetoothSppClient(this, this).also { it.start() }
    }

    // ---------- Feeder (FD-2.1) ----------

    /** Starts connecting to the feeder in the background. */
    private fun startFeeder() {
        if (feederLink != null) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            showFeederStatus("This phone has no Bluetooth", false)
            return
        }
        val connector = ClassicBluetoothConnector(this, adapter, FEEDER_NAME)
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

    /** Called when Feed Now is tapped: sends FEED to the feeder. */
    private fun feedNow() {
        val link = feederLink ?: return
        buttonFeedNow.isEnabled = false       // one tap = one command, no double taps
        showFeederStatus("Sending…", true)

        feederSender.execute {
            val sent = link.send("FEED")
            runOnUiThread {
                if (!sent) showFeederStatus("Could not reach the feeder", false)
                // Same 3 seconds as the feeder's cooldown, then the button works again
                buttonFeedNow.postDelayed({ buttonFeedNow.isEnabled = feederConnected }, FEED_LOCK_MS)
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
                buttonFeedNow.isEnabled = feederConnected
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
            // Step 8.3 shows the feeder's reply as it is. Step 8.4 turns it into a clear message.
            runOnUiThread {
                if (number != feederConnectionNumber) return@runOnUiThread
                showFeederStatus("Feeder: $line", true)
            }
        }
    }

    private fun showFeederStatus(message: String, good: Boolean) {
        textFeeder.text = message
        textFeeder.setTextColor(if (good) 0xFF15803D.toInt() else 0xFFDC2626.toInt())
    }

    private companion object {
        const val FEEDER_NAME = "PetFeeder_ESP32"
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
        if (!::textConnection.isInitialized) return
        textConnection.text = message
        textConnection.setTextColor(if (connected) 0xFF15803D.toInt() else 0xFFDC2626.toInt())
    }

    override fun onLineReceived(line: String) {
        val motion = CollarProtocol.parseMotion(line) ?: return
        textActivity.text= "Motion: ax %.2f  ay %.2f  az %.2f g".format(
            motion.ax, motion.ay, motion.az
        )
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




