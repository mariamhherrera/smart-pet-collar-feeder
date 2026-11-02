package com.example.pettracker.bluetooth

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/*
 * Shared Bluetooth connection code, used for the collar and for the feeder.
 *
 * BluetoothLink keeps ONE device connected: it searches for it, connects, passes every
 * received line to the listener, and when the connection drops it searches and connects
 * again until stop() is called. It has no Android imports, so it is unit tested on the
 * JVM. The Android part (discovery, RFCOMM sockets) is ClassicBluetoothConnector.
 *
 * The message format that travels over the connection is in docs/bluetooth-messages.md.
 */

/** An open connection to a device. Messages are text lines. */
interface Connection : Closeable {
    val deviceName: String

    /**
     * Blocks until the next line arrives.
     * Returns null when the device closed the connection and throws [IOException] when the
     * link broke. Calling [close] from another thread must end a blocked call this way.
     */
    fun readLine(): String?

    /**
     * Sends one line to the device. A newline is added at the end.
     * Throws [IOException] when the link broke.
     */
    fun writeLine(line: String)

    /** Must not throw, and must be safe to call more than once. */
    override fun close()
}

/** Finds a device and opens a [Connection] to it. These calls block, so the link runs them on its own thread. */
interface Connector {
    /** True when the device was found (or is already known), false when it was not seen this time. */
    fun search(): Boolean

    /** Opens a connection to the device found by [search]. Throws [IOException] when that fails. */
    fun open(): Connection

    /** Forgets the device found earlier, so the next [search] starts from scratch. */
    fun forget()

    /** Ends a [search] or [open] that is in progress. Called by [BluetoothLink.stop]. */
    fun cancel()
}

enum class LinkState {
    STOPPED,

    /** Looking for the device, not connected yet. */
    SEARCHING,

    /** Device found, opening the connection. */
    CONNECTING,

    CONNECTED,

    /** The connection was lost; searching and connecting again. */
    RECONNECTING
}

/** Both methods are called on the link's own thread, not on the main thread. */
interface LinkListener {
    /** [deviceName] is only set in the CONNECTED state. */
    fun onState(state: LinkState, deviceName: String?)

    fun onLine(line: String)
}

class LinkConfig(
    /** Drop the connection when nothing arrived for this long (silent drops). null = never. */
    val silenceTimeoutMs: Long? = null,
    /** Wait before trying again after a failed or lost connection. Doubles on each failure. */
    val retryDelayMs: Long = 1_000,
    val maxRetryDelayMs: Long = 10_000,
    /** After this many failed connects in a row, forget the device and search for it again. */
    val forgetDeviceAfterFailures: Int = 3
)

class BluetoothLink(
    private val connector: Connector,
    private val config: LinkConfig,
    private val listener: LinkListener
) {
    @Volatile private var running = false
    @Volatile private var current: Connection? = null
    @Volatile private var lastDataAt = 0L

    private var thread: Thread? = null
    private var watchdog: ScheduledExecutorService? = null

    // Only used by the link thread
    private var reportedState: LinkState? = null
    private var reportedName: String? = null

    /** Starts searching and connecting in the background. A link can only be started once. */
    @Synchronized
    fun start() {
        check(thread == null) { "BluetoothLink can only be started once" }
        running = true
        config.silenceTimeoutMs?.let { startWatchdog(it) }
        thread = Thread(::loop, "BluetoothLink").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Disconnects and stops for good. Safe to call from any thread, does not block. */
    @Synchronized
    fun stop() {
        running = false
        connector.cancel()
        closeQuietly(current)
        watchdog?.shutdownNow()
        thread?.interrupt()
    }

    /**
     * Sends one line to the connected device, for example "FEED".
     * Returns true when it was sent, false when no device is connected or sending failed.
     * When sending fails, the connection is closed so the link reconnects.
     * Safe to call from any thread, but it can block for a moment, so not from the main thread.
     */
    fun send(line: String): Boolean {
        val connection = current ?: return false
        return try {
            connection.writeLine(line)
            true
        } catch (e: IOException) {
            closeQuietly(connection) // makes readLine() in serve() fail, so the link reconnects
            false
        }
    }

    private fun loop() {
        var delayMs = config.retryDelayMs
        var failedOpens = 0
        var wasConnected = false
        var deviceFound = false

        try {
            while (running) {
                report(
                    when {
                        wasConnected -> LinkState.RECONNECTING
                        deviceFound -> LinkState.CONNECTING
                        else -> LinkState.SEARCHING
                    }
                )

                deviceFound = searchOrFalse() && running

                if (deviceFound) {
                    report(if (wasConnected) LinkState.RECONNECTING else LinkState.CONNECTING)
                    val connection = openOrNull()

                    if (connection != null) {
                        failedOpens = 0
                        delayMs = config.retryDelayMs
                        wasConnected = true
                        serve(connection)
                        if (running) report(LinkState.RECONNECTING)
                    } else if (++failedOpens >= config.forgetDeviceAfterFailures) {
                        connector.forget()
                        deviceFound = false
                        failedOpens = 0
                    }
                }

                if (!pause(delayMs)) break
                delayMs = minOf(delayMs * 2, config.maxRetryDelayMs)
            }
        } finally {
            running = false
            report(LinkState.STOPPED)
        }
    }

    /** Reads lines from the connection until it drops or the link is stopped. */
    private fun serve(connection: Connection) {
        current = connection
        lastDataAt = now()
        try {
            // stop() may have run before it could see this connection
            if (!running) return

            report(LinkState.CONNECTED, connection.deviceName)
            while (running) {
                val line = connection.readLine() ?: return // the device closed the connection
                lastDataAt = now()
                listener.onLine(line)
            }
        } catch (e: IOException) {
            // The link broke, or the watchdog or stop() closed it. The caller connects again.
        } finally {
            current = null
            connection.close()
        }
    }

    // stop() interrupts the thread, which a connector may report as InterruptedException.
    // That must not kill the thread, the loop sees that running is false and ends normally.

    private fun searchOrFalse(): Boolean =
        try {
            connector.search()
        } catch (e: IOException) {
            false
        } catch (e: InterruptedException) {
            false
        }

    private fun openOrNull(): Connection? {
        val connection = try {
            connector.open()
        } catch (e: IOException) {
            return null
        } catch (e: InterruptedException) {
            return null
        }
        if (!running) {
            connection.close()
            return null
        }
        return connection
    }

    /** Waits [ms]. Returns false when the link was stopped meanwhile. */
    private fun pause(ms: Long): Boolean {
        if (!running) return false
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // stop() wakes the thread up this way
        }
        return running
    }

    private fun startWatchdog(timeoutMs: Long) {
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "BluetoothLink-watchdog").also { it.isDaemon = true }
        }
        val periodMs = (timeoutMs / 4).coerceIn(50L, 1_000L)
        executor.scheduleWithFixedDelay({
            val connection = current
            if (connection != null && now() - lastDataAt > timeoutMs) {
                connection.close() // makes readLine() in serve() fail, so the link reconnects
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS)
        watchdog = executor
    }

    private fun report(state: LinkState, deviceName: String? = null) {
        if (state == reportedState && deviceName == reportedName) return
        reportedState = state
        reportedName = deviceName
        listener.onState(state, deviceName)
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (e: IOException) {
            // nothing more to do
        }
    }

    private fun now() = System.nanoTime() / 1_000_000
}