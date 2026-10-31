package com.example.pettracker

iport android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompact
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth

class HomeActivity : Activity(), BluetoothSppClient.Listener {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseHelper
    private var collarClient: BluetoothSppClient? = null
    private lateinit var textConnection: TextView
    private lateinit var textActivity: TextView
    private lateinit var searchButton: Button
    private var permissionRequest = 410

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

        val buttonProfile =
            findViewById<Button>(R.id.buttonProfile)

        val buttonLogout =
            findViewById<Button>(R.id.buttonLogout)

        buttonProfile.setOnClickListener {

            startActivity(
                Intent(this, ProfileActivity::class.java)
            )
        }

        buttonLogout.setOnClickListener {

            // Logout from Firebase
            auth.signOut()
            collarClient?.stop()
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
        if (bluetoothClient != null) return
        bluetoothClient = BluetoothSppClient(this, this).also { it.start() }
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
        if (!::connectionText.isInitialized) return
        connectionText.text = message
        connectionText.setTextColor(if (connected) 0xFF15803D.toInt() else 0xFFDC2626.toInt())
    }

    override fun onLineReceived(line: String) {
        val motion = CollarProtocol.parseMotion(line) ?: return
        activityText.text = "Motion: ax %.2f  ay %.2f  az %.2f g".format(
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




