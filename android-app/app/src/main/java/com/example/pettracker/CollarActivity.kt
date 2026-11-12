package com.example.pettracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Collar screen (bottom bar): collar connection, heart rate and activity.
 * Connects to the collar the same way Home does (BluetoothSppClient).
 * Heart rate (COL-5.3) and the activity label and minutes (COL-2.2, COL-2.3)
 * show "--" until those tasks send real values.
 */
class CollarActivity : Activity(), BluetoothSppClient.Listener {

    private var collarClient: BluetoothSppClient? = null
    private lateinit var textStatus: TextView
    private lateinit var textMotion: TextView
    private val permissionRequest = 411

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collar)
        BottomNav.setUp(this, BottomNav.COLLAR)

        textStatus = findViewById(R.id.textCollarStatus)
        textMotion = findViewById(R.id.textCollarMotion)

        findViewById<Button>(R.id.buttonSearchCollar).setOnClickListener {
            if (hasBluetoothPermissions()) collarClient?.searchNow()
            else requestBluetoothPermissions()
        }

        // Ask once here (asking in onResume would ask again every time the pop-up closes)
        if (!hasBluetoothPermissions()) requestBluetoothPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasBluetoothPermissions()) startCollar()
    }

    override fun onPause() {
        super.onPause()
        // Only one screen can talk to the collar at a time, so let go when leaving
        collarClient?.stop()
        collarClient = null
    }

    private fun startCollar() {
        if (collarClient != null) return
        collarClient = BluetoothSppClient(this, this).also { it.start() }
    }

    // ---------- What the collar sends ----------

    override fun onStatus(message: String, connected: Boolean) {
        textStatus.text = message
        textStatus.setTextColor(if (connected) 0xFF15803D.toInt() else 0xFFDC2626.toInt())
    }

    override fun onLineReceived(line: String) {
        val motion = CollarProtocol.parseMotion(line) ?: return
        textMotion.text = "Motion: ax %.2f  ay %.2f  az %.2f g".format(
            motion.ax, motion.ay, motion.az
        )
    }

    // ---------- Bluetooth permissions (same as Home) ----------

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
        if (requestCode == permissionRequest) {
            if (hasBluetoothPermissions()) startCollar()
            else onStatus("Bluetooth permissions denied", false)
        }
    }
}
