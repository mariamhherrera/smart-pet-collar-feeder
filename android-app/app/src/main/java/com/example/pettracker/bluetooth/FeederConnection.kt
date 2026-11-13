package com.example.pettracker.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * A ready-made connection to the feeder for one screen, so each screen does not have to
 * handle threads itself:
 * - connects to PetFeeder_ESP32 in the background (with BluetoothLink)
 * - sends commands off the main thread
 * - calls the listener on the main thread, so it can change the screen directly
 *
 * Use one per screen: start() in onResume, stop() in onPause, close() in onDestroy.
 * The ESP32 accepts one phone at a time, so only the screen that is open stays connected.
 */
class FeederConnection(
    private val activity: Activity,
    private val listener: Listener
) {
    /** All methods are called on the main thread. */
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onReply(reply: FeederReply)
        fun onSendFailed()
    }

    private val sender = Executors.newSingleThreadExecutor()
    private var link: BluetoothLink? = null
    private var connectionNumber = 0 // goes up for every new connection, to ignore messages from old ones

    /** True while the feeder is connected. */
    var isConnected = false
        private set

    /** Starts connecting. Returns false when the phone has no Bluetooth. */
    fun start(): Boolean {
        if (link != null) return true
        val hasPermission = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= 23) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasPermission) return false

        val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        connectionNumber++
        val number = connectionNumber
        val connector = ClassicBluetoothConnector(activity, adapter, FeederMessage.DEVICE_NAME)
        // No silence timeout: the feeder only talks when it answers a command
        link = BluetoothLink(connector, LinkConfig(), object : LinkListener {
            override fun onState(state: LinkState, deviceName: String?) {
                activity.runOnUiThread {
                    if (number != connectionNumber) return@runOnUiThread
                    val connected = state == LinkState.CONNECTED
                    if (connected != isConnected) {
                        isConnected = connected
                        listener.onConnectionChanged(connected)
                    }
                }
            }

            override fun onLine(line: String) {
                val reply = FeederMessage.parse(line) ?: return // not a feeder reply, ignore it
                activity.runOnUiThread {
                    if (number == connectionNumber) listener.onReply(reply)
                }
            }
        }).also { it.start() }
        return true
    }

    /** Disconnects. A link can only be started once, so start() makes a new one next time. */
    fun stop() {
        link?.stop()
        link = null
        connectionNumber++ // from now on, messages from the old connection are ignored
        isConnected = false
    }

    /** Sends one command, for example FeederMessage.feedCommand(2). */
    fun send(command: String) {
        val current = link ?: return listener.onSendFailed()
        sender.execute {
            val sent = current.send(command)
            if (!sent) activity.runOnUiThread { listener.onSendFailed() }
        }
    }

    /** Call in onDestroy. */
    fun close() {
        stop()
        sender.shutdown()
    }
}
