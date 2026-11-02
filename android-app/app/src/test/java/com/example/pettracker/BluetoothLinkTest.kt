package com.example.pettracker

import com.example.pettracker.bluetooth.BluetoothLink
import com.example.pettracker.bluetooth.Connection
import com.example.pettracker.bluetooth.Connector
import com.example.pettracker.bluetooth.LinkConfig
import com.example.pettracker.bluetooth.LinkListener
import com.example.pettracker.bluetooth.LinkState
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Tests the search / connect / reconnect logic with a fake device, no Bluetooth needed. */
class BluetoothLinkTest {

    private class FakeConnection : Connection {
        override val deviceName = "FakeCollar"

        private val events = LinkedBlockingQueue<Any>()

        @Volatile var closed = false

        /** Lines the app sent to the device with writeLine(). */
        val written = LinkedBlockingQueue<String>()
        @Volatile var failWrites = false

        override fun writeLine(line: String) {
            if (closed || failWrites) throw IOException("link lost")
            written.put(line)
        }

        fun send(line: String) = events.put(line)
        fun breakLink() = events.put(BROKEN)
        fun closeFromDeviceSide() = events.put(END)

        override fun readLine(): String? =
            when (val event = events.take()) {
                is String -> event
                END -> null
                else -> throw IOException("link lost")
            }

        override fun close() {
            closed = true
            events.offer(BROKEN) // ends a read that is waiting
        }

        companion object {
            val BROKEN = Any()
            val END = Any()
        }
    }

    private class FakeConnector : Connector {
        @Volatile var visible = true
        @Volatile var failingOpens = 0
        @Volatile var searchBlocksUntilCancelled = false

        val searches = AtomicInteger()
        val forgets = AtomicInteger()
        val opened = LinkedBlockingQueue<FakeConnection>()

        private val cancelled = CountDownLatch(1)

        override fun search(): Boolean {
            searches.incrementAndGet()
            if (searchBlocksUntilCancelled) {
                cancelled.await()
                return false
            }
            return visible
        }

        override fun open(): Connection {
            if (failingOpens > 0) {
                failingOpens--
                throw IOException("connect failed")
            }
            return FakeConnection().also { opened.put(it) }
        }

        override fun forget() {
            forgets.incrementAndGet()
        }

        override fun cancel() {
            cancelled.countDown()
        }
    }

    private class Recorder : LinkListener {
        val states = CopyOnWriteArrayList<LinkState>()
        val deviceNames = CopyOnWriteArrayList<String?>()
        val lines = LinkedBlockingQueue<String>()

        override fun onState(state: LinkState, deviceName: String?) {
            states.add(state)
            deviceNames.add(deviceName)
        }

        override fun onLine(line: String) {
            lines.put(line)
        }

        fun nextLine(): String? = lines.poll(WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private val connector = FakeConnector()
    private val recorder = Recorder()
    private var link: BluetoothLink? = null

    // On Android an exception that nobody catches on the link thread would crash the app
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun watchForCrashes() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
    }

    @After
    fun stopLinkAndCheckItDidNotCrash() {
        link?.stop()

        Thread.getAllStackTraces().keys
            .filter { it.name == "BluetoothLink" }
            .forEach { it.join(WAIT_MS) }

        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        assertEquals("Uncaught exceptions on the link thread", emptyList<Throwable>(), uncaught.toList())
    }

    private fun startLink(
        silenceTimeoutMs: Long? = null,
        forgetAfterFailures: Int = 3
    ) {
        link = BluetoothLink(
            connector,
            LinkConfig(
                silenceTimeoutMs = silenceTimeoutMs,
                retryDelayMs = 10,
                maxRetryDelayMs = 40,
                forgetDeviceAfterFailures = forgetAfterFailures
            ),
            recorder
        ).also { it.start() }
    }

    private fun nextConnection(): FakeConnection =
        connector.opened.poll(WAIT_MS, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("The link did not open a connection")

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun searchesConnectsAndDeliversLines() {
        startLink()

        val connection = nextConnection()
        connection.send("one")
        connection.send("two")

        assertEquals("one", recorder.nextLine())
        assertEquals("two", recorder.nextLine())
        assertEquals(
            listOf(LinkState.SEARCHING, LinkState.CONNECTING, LinkState.CONNECTED),
            recorder.states
        )
        assertEquals("FakeCollar", recorder.deviceNames.last())
    }

    @Test
    fun reconnectsAfterTheLinkBreaks() {
        startLink()

        val first = nextConnection()
        first.send("before")
        assertEquals("before", recorder.nextLine())

        first.breakLink()

        val second = nextConnection()
        second.send("after")
        assertEquals("after", recorder.nextLine())

        assertTrue(first.closed)
        assertEquals(
            listOf(
                LinkState.SEARCHING, LinkState.CONNECTING, LinkState.CONNECTED,
                LinkState.RECONNECTING, LinkState.CONNECTED
            ),
            recorder.states
        )
    }

    @Test
    fun reconnectsWhenTheDeviceClosesTheConnection() {
        startLink()

        nextConnection().closeFromDeviceSide()

        val second = nextConnection()
        second.send("after")
        assertEquals("after", recorder.nextLine())
    }

    @Test
    fun reconnectsEveryTimeTheLinkDrops() {
        startLink()

        var connection = nextConnection()
        repeat(5) { round ->
            connection.send("round $round")
            assertEquals("round $round", recorder.nextLine())

            connection.breakLink()
            connection = nextConnection()
        }
    }

    @Test
    fun keepsSearchingUntilTheDeviceAppears() {
        connector.visible = false
        startLink()

        waitFor("three searches") { connector.searches.get() >= 3 }
        assertTrue(connector.opened.isEmpty())
        assertEquals(setOf(LinkState.SEARCHING), recorder.states.toSet())

        connector.visible = true

        nextConnection().send("found")
        assertEquals("found", recorder.nextLine())
    }

    @Test
    fun keepsTryingWhenConnectingFailsAndForgetsTheDevice() {
        connector.failingOpens = 3
        startLink(forgetAfterFailures = 3)

        nextConnection().send("connected at last")
        assertEquals("connected at last", recorder.nextLine())

        assertEquals(1, connector.forgets.get())
        // CONNECTING while the known device is retried, SEARCHING again once it was forgotten
        assertEquals(
            listOf(
                LinkState.SEARCHING, LinkState.CONNECTING,
                LinkState.SEARCHING, LinkState.CONNECTING, LinkState.CONNECTED
            ),
            recorder.states
        )
    }

    @Test
    fun dropsAConnectionThatWentSilentAndReconnects() {
        startLink(silenceTimeoutMs = 150)

        val first = nextConnection() // never sends anything

        val second = nextConnection()
        assertTrue(first.closed)

        second.send("alive")
        assertEquals("alive", recorder.nextLine())
        assertTrue(recorder.states.contains(LinkState.RECONNECTING))
    }

    @Test
    fun keepsAConnectionThatKeepsSending() {
        startLink(silenceTimeoutMs = 150)

        val connection = nextConnection()
        repeat(15) {
            connection.send("tick")
            assertEquals("tick", recorder.nextLine())
            Thread.sleep(40) // about 600 ms in total, much longer than the timeout
        }

        assertFalse(connection.closed)
        assertTrue(connector.opened.isEmpty())
    }

    @Test
    fun stopClosesTheConnectionAndDoesNotReconnect() {
        startLink()

        val connection = nextConnection()
        connection.send("hello")
        assertEquals("hello", recorder.nextLine())

        link!!.stop()

        waitFor("the link to report STOPPED") { recorder.states.last() == LinkState.STOPPED }
        assertTrue(connection.closed)

        Thread.sleep(100)
        assertTrue(connector.opened.isEmpty())
    }

    @Test
    fun stopEndsASearchThatIsInProgress() {
        connector.searchBlocksUntilCancelled = true
        startLink()

        waitFor("the search to start") { connector.searches.get() == 1 }

        link!!.stop()

        waitFor("the link to report STOPPED") { recorder.states.last() == LinkState.STOPPED }
        assertEquals(1, connector.searches.get())
    }

    @Test
    fun sendsALineToTheConnectedDevice() {
        startLink()

        val connection = nextConnection()
        waitFor("the link to be connected") { recorder.states.contains(LinkState.CONNECTED) }

        assertTrue(link!!.send("FEED"))
        assertEquals("FEED", connection.written.poll(WAIT_MS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun sendReturnsFalseWhenNotConnected() {
        connector.visible = false
        startLink()

        waitFor("a search") { connector.searches.get() >= 1 }
        assertFalse(link!!.send("FEED"))
    }

    @Test
    fun reconnectsWhenSendingFails() {
        startLink()

        val first = nextConnection()
        waitFor("the link to be connected") { recorder.states.contains(LinkState.CONNECTED) }
        first.failWrites = true

        assertFalse(link!!.send("FEED"))

        val second = nextConnection()
        assertTrue(first.closed)
        waitFor("sending on the new connection") { link!!.send("FEED") }
        assertEquals("FEED", second.written.poll(WAIT_MS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun canOnlyBeStartedOnce() {
        startLink()
        assertNotNull(link)

        try {
            link!!.start()
            fail("Starting twice should fail")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    private companion object {
        const val WAIT_MS = 3_000L
    }
}