package com.example.pettracker.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds a device by its Bluetooth name and opens a serial (SPP / RFCOMM) connection to it.
 *
 * The ESP32 firmware uses BluetoothSerial, which is classic Bluetooth, not Bluetooth Low Energy.
 * That is why this uses discovery and RFCOMM sockets instead of a BLE scanner and GATT.
 *
 * Paired devices are checked first, which is instant. Otherwise the phone scans for
 * [scanTimeoutMs]; connecting to an unpaired device makes Android show its pairing dialog.
 *
 * One instance is used by one BluetoothLink. The caller must already hold the Bluetooth
 * permissions (HomeActivity checks that), which is why the lint warning is suppressed.
 */
@SuppressLint("MissingPermission")
class ClassicBluetoothConnector(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val deviceName: String,
    private val scanTimeoutMs: Long = 12_000
) : Connector {

    private val context = context.applicationContext

    @Volatile private var cancelled = false
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    @Volatile private var scanDone: CountDownLatch? = null

    override fun search(): Boolean {
        if (cancelled || !adapter.isEnabled) return false
        if (device != null) return true

        device = try {
            adapter.bondedDevices.orEmpty().firstOrNull { it.name == deviceName } ?: discover()
        } catch (e: SecurityException) {
            null
        }
        return device != null
    }

    override fun open(): Connection {
        val target = device ?: throw IOException("$deviceName has not been found yet")
        if (cancelled) throw IOException("Cancelled")

        try {
            // A scan that is still running makes connect() slow and unreliable
            adapter.cancelDiscovery()

            val socket = target.createRfcommSocketToServiceRecord(SPP_UUID)
            pendingSocket = socket
            try {
                socket.connect()
                return SocketConnection(socket, target.name ?: deviceName)
            } catch (e: IOException) {
                closeQuietly(socket)
                throw e
            } finally {
                pendingSocket = null
            }
        } catch (e: SecurityException) {
            throw IOException("Bluetooth permission is missing", e)
        }
    }

    override fun forget() {
        device = null
    }

    override fun cancel() {
        cancelled = true
        scanDone?.countDown()
        pendingSocket?.let { closeQuietly(it) } // makes a blocked connect() fail
    }

    /** Scans until the device with the wanted name shows up, the scan ends or [cancel] is called. */
    private fun discover(): BluetoothDevice? {
        val match = AtomicReference<BluetoothDevice?>()
        val done = CountDownLatch(1)
        scanDone = done

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    // The name can arrive late, in a second broadcast
                    BluetoothDevice.ACTION_FOUND,
                    BluetoothDevice.ACTION_NAME_CHANGED -> {
                        val found = intent.bluetoothDevice() ?: return
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: found.name
                        if (name == deviceName) {
                            match.set(found)
                            done.countDown()
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> done.countDown()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        try {
            adapter.cancelDiscovery()
            if (cancelled || !adapter.startDiscovery()) return null

            try {
                done.await(scanTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // BluetoothLink.stop()
            }
            return match.get()
        } finally {
            adapter.cancelDiscovery()
            scanDone = null
            context.unregisterReceiver(receiver)
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun closeQuietly(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // already closed
        }
    }

    private class SocketConnection(
        private val socket: BluetoothSocket,
        override val deviceName: String
    ) : Connection {

        private val reader = LineReader(socket.inputStream)
        private val output = socket.outputStream

        override fun readLine(): String? = reader.readLine()

        // synchronized: two lines sent at the same time must not get mixed together
        @Synchronized
        override fun writeLine(line: String) {
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        override fun close() {
            try {
                socket.close()
            } catch (e: IOException) {
                // already closed
            }
        }
    }

    companion object {
        /** The standard Serial Port Profile UUID, which the ESP32 BluetoothSerial library uses. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}