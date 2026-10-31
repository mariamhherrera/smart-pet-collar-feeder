package com.example.pettracker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth Classic SPP client for the ESP32 collar.
 * Uses newline-delimited UTF-8 records. The current collar firmware sends
 * seven-column CSV: time_s, ax, ay, az, gx, gy, gz.
 */
class BluetoothSppClient(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String, connected: Boolean)
        fun onLineReceived(line: String)
    }

    companion object {
        const val DEVICE_NAME = "PetCollar_ESP32"
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val RETRY_MS = 3000L
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var connecting = false
    @Volatile private var discoveryReceiverRegistered = false
    @Volatile private var target: BluetoothDevice? = null

    private val retry = object : Runnable {
        override fun run() {
            if (!stopped.get() && socket?.isConnected != true) {
                findAndConnect()
            }
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_FOUND) return
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (device != null && device.name == DEVICE_NAME) {
                target = device
                adapter?.cancelDiscovery()
                connect(device)
            }
        }
    }

    fun start() {
        stopped.set(false)
        findAndConnect()
    }

    fun searchNow() {
        main.removeCallbacks(retry)
        findAndConnect()
    }

    fun stop() {
        stopped.set(true)
        main.removeCallbacks(retry)
        try {
            if (discoveryReceiverRegistered) {
                context.unregisterReceiver(discoveryReceiver)
                discoveryReceiverRegistered = false
            }
        } catch (_: Exception) {}
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        closeSocket()
        worker.shutdownNow()
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun findAndConnect() {
        if (stopped.get()) return
        if (adapter == null) {
            status("This device does not support Bluetooth", false); return
        }
        if (!hasBluetoothPermission()) {
            status("Bluetooth permission is required", false); return
        }
        if (!adapter.isEnabled) {
            status("Turn on Bluetooth to find the collar", false)
            scheduleRetry()
            return
        }
        if (socket?.isConnected == true) return
        if (connecting) return

        try {
            val bonded = adapter.bondedDevices
            val match = bonded.firstOrNull { it.name == DEVICE_NAME }
            if (match != null) {
                target = match
                connect(match)
                return
            }
            status("Searching for $DEVICE_NAME…", false)
            if (!discoveryReceiverRegistered) {
                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                if (Build.VERSION.SDK_INT >= 33)
                    context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else
                    @Suppress("DEPRECATION")
                    context.registerReceiver(discoveryReceiver, filter)
                discoveryReceiverRegistered = true
            }
            adapter.cancelDiscovery()
            adapter.startDiscovery()
            scheduleRetry()
        } catch (e: SecurityException) {
            status("Bluetooth permission is required", false)
        } catch (e: Exception) {
            status("Bluetooth search failed: ${e.message ?: "unknown error"}", false)
            scheduleRetry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (stopped.get() || connecting || socket?.isConnected == true) return
        connecting = true
        status("Connecting to ${device.name ?: DEVICE_NAME}…", false)
        worker.execute {
            var candidate: BluetoothSocket? = null
            try {
                adapter?.cancelDiscovery()
                candidate = device.createRfcommSocketToServiceRecord(SPP_UUID)
                candidate.connect()
                if (stopped.get()) {
                    candidate.close()
                    return@execute
                }
                socket = candidate
                connecting = false
                status("Connected to ${device.name ?: DEVICE_NAME}", true)
                readLoop(candidate)
            } catch (e: Exception) {
                try { candidate?.close() } catch (_: Exception) {}
                socket = null
                connecting = false
                status("Connection lost. Reconnecting…", false)
                scheduleRetry()
            }
        }
    }

    private fun readLoop(active: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(active.inputStream, Charsets.UTF_8))
            while (!stopped.get() && active.isConnected) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) main.post { listener.onLineReceived(line.trim()) }
            }
        } catch (_: Exception) {
        } finally {
            try { active.close() } catch (_: Exception) {}
            if (socket === active) socket = null
            connecting = false
            if (!stopped.get()) {
                status("Connection lost. Reconnecting…", false)
                scheduleRetry()
            }
        }
    }

    fun sendMessage(message: String): Boolean {
        val active = socket ?: return false
        return try {
            synchronized(active) {
                active.outputStream.write((message.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
                active.outputStream.flush()
            }
            true
        } catch (_: Exception) {
            scheduleRetry()
            false
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun scheduleRetry() {
        if (!stopped.get()) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, RETRY_MS)
        }
    }

    private fun status(message: String, connected: Boolean) {
        main.post { if (!stopped.get()) listener.onStatus(message, connected) }
    }
}
