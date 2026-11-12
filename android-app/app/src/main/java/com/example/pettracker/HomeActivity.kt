package com.example.pettracker

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
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
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

class HomeActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseHelper
    private lateinit var textConnection: TextView
    private lateinit var textActivity: TextView
    private var cloud: PetCloudRepository? = null
    private var collarListener: ValueEventListener? = null
    private var feedingListener: ValueEventListener? = null
    private var feederStatusListener: ValueEventListener? = null

    // Collar online check: the collar sends every 5 seconds, so 30 seconds of silence = offline
    private var lastCollarReadingTime = 0L
    private val clockHandler = Handler(Looper.getMainLooper())
    private val refreshCollarStatus = object : Runnable {
        override fun run() {
            showCollarOnline()
            clockHandler.postDelayed(this, 10_000L)
        }
    }

    // UI-8.3: meals given today, from the feeding history in Firebase
    private var mealsToday = 0
    private var petSpecies: String? = null

    // Feeder (FD-2.1): one Bluetooth link to PetFeeder_ESP32
    private var feederLink: BluetoothLink? = null
    private var feederConnected = false
    private var feederConnectionNumber = 0 // goes up for every new connection, to ignore messages from old ones
    private val feederSender = Executors.newSingleThreadExecutor() // sending can block, so not on the main thread
    private lateinit var textFeeder: TextView
    private lateinit var buttonFeedNow: Button
    private lateinit var textFeedHistory: TextView
    private lateinit var feedPortionGroup: RadioGroup
    private lateinit var textMealsToday: TextView

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
        textFeeder = findViewById(R.id.textFeeder)
        textFeedHistory = findViewById(R.id.textFeedHistory)
        textMealsToday = findViewById(R.id.textMealsToday)
        buttonFeedNow = findViewById(R.id.buttonFeedNow)
        feedPortionGroup = findViewById(R.id.homeFeedPortionGroup)
        buttonFeedNow.setOnClickListener { sendFeedNowToCloud() }

        // UI-3.3: bottom bar and profile icon. Pet profile opens from the icon, Log out is in
        // Settings, and "Search for collar" (Bluetooth backup) is on the Collar page.
        BottomNav.setUp(this, BottomNav.HOME)

        // Shortcut from the Next meal card to the feeding schedule
        findViewById<Button>(R.id.buttonSchedule).setOnClickListener {
            startActivity(Intent(this, ScheduleActivity::class.java))
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
        clockHandler.post(refreshCollarStatus)
    }

    override fun onPause() {
        super.onPause()
        detachCloudListeners()
        clockHandler.removeCallbacks(refreshCollarStatus)
        stopFeeder()
    }

    override fun onDestroy() {
        super.onDestroy()
        feederSender.shutdown()
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

    /** Called when Feed Now is tapped: sends FEED to the feeder. */
    private fun feedNow() {
        val link = feederLink ?: return
        setFeedNowEnabled(false)              // one tap = one command, no double taps
        showFeederStatus("Sending…", true)

        feederSender.execute {
            val sent = link.send(FeederMessage.FEED)
            runOnUiThread {
                if (!sent) showFeederStatus("Could not reach the feeder", false)
                // Same 3 seconds as the feeder's cooldown, then the button works again
                buttonFeedNow.postDelayed({ setFeedNowEnabled(feederConnected) }, FEED_LOCK_MS)
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
                setFeedNowEnabled(feederConnected)
                if (feederConnected) sendTimeAndSchedule()
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
        const val FEEDINGS_TO_LOAD = 20
    }

    private fun showPetInformation() {
        val user = auth.currentUser ?: return
        val profile = database.getPetProfile(user.uid)
        petSpecies = profile?.species

        findViewById<TextView>(R.id.textWelcome).text =
            "Welcome, ${user.email ?: "User"}"

        findViewById<TextView>(R.id.textPetName).text =
            profile?.name?.let { "Hi, $it!" } ?: "Add your pet"

        showNextMeal()
        showMealsToday()
    }

    /** Next meal card: the time and portion saved on the Feeder page. */
    private fun showNextMeal() {
        val schedule = ScheduleStorage(this)
        val textNextMeal = findViewById<TextView>(R.id.textNextMeal)
        val textNextPortion = findViewById<TextView>(R.id.textNextPortion)

        if (!schedule.hasSchedule()) {
            textNextMeal.text = "Not set"
            textNextPortion.text = "Set a schedule"
            return
        }

        // Write the time the way the phone does (6:00 PM or 18:00)
        val time = Calendar.getInstance()
        time.set(Calendar.HOUR_OF_DAY, schedule.hour)
        time.set(Calendar.MINUTE, schedule.minute)
        textNextMeal.text = android.text.format.DateFormat.getTimeFormat(this).format(time.time)
        textNextPortion.text = portionName(schedule.portion) + " portion"
    }

    /** UI-8.3: "1 of 3 meals today", using the limit from Settings. */
    private fun showMealsToday() {
        val limit = SettingsStorage(this).mealLimit
        textMealsToday.text = "$mealsToday of $limit meals today"
        textMealsToday.setTextColor(
            if (MealLimit.canFeed(mealsToday, limit)) getColor(R.color.text_secondary) else 0xFFDC2626.toInt()
        )
    }

    /** Collar tile: online if a reading came in the last 30 seconds. */
    private fun showCollarOnline() {
        if (lastCollarReadingTime == 0L) {
            textConnection.text = "Waiting for the collar"
            textConnection.setTextColor(getColor(R.color.text_secondary))
            return
        }
        val online = System.currentTimeMillis() - lastCollarReadingTime < 30_000L
        textConnection.text = if (online) "Online" else "Offline since " + clock(lastCollarReadingTime)
        textConnection.setTextColor(if (online) 0xFF15803D.toInt() else 0xFFDC2626.toInt())
    }

    /** 1, 2, 3 -> Small, Medium, Large */
    private fun portionName(portion: Any?): String = when (portion?.toString()) {
        null -> "—"
        "1" -> "Small"
        "2" -> "Medium"
        "3" -> "Large"
        else -> portion.toString()
    }

    /** "4:05 PM" for today, or "Nov 10, 2026, 4:05 PM" for another day. */
    private fun clock(time: Long): String {
        val timeText = android.text.format.DateFormat.getTimeFormat(this).format(Date(time))
        return if (MealLimit.startOfDay(time) == MealLimit.startOfDay(System.currentTimeMillis())) timeText
        else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time)) + ", " + timeText
    }

    private fun attachCloudListeners() {
        if (collarListener != null || feedingListener != null) return
        val uid = auth.currentUser?.uid ?: return
        val cloud = PetCloudRepository(uid)
        this.cloud = cloud

        // Collar: newest reading (live motion, heart rate), also saved in the history (UI-6.1)
        collarListener = cloud.listenToLatestCollar(
            onValue = { row ->
                runOnUiThread {
                    val reading = CollarReading.fromMap(row) ?: return@runOnUiThread
                    lastCollarReadingTime = if (reading.timeMillis > 0) reading.timeMillis else System.currentTimeMillis()
                    showCollarOnline()
                    cloud.saveReadingToHistory(reading)

                    textActivity.text = "X %.2f · Y %.2f · Z %.2f g".format(reading.ax, reading.ay, reading.az)
                    findViewById<TextView>(R.id.textActivityTime).text = "Updated " + clock(lastCollarReadingTime)
                    showHeartRate(reading.heartRate)
                }
            },
            onError = { message -> runOnUiThread { textConnection.text = "Firebase error: $message" } }
        )

        // Feeder: online or offline, once the feeder writes its status to Firebase
        feederStatusListener = cloud.listenToFeederStatus(
            onValue = { status ->
                runOnUiThread {
                    val text = findViewById<TextView>(R.id.textFeederConnection)
                    val connected = status?.get("connected") == true
                    text.text = when {
                        status == null -> "Waiting for the feeder"
                        connected -> "Online"
                        else -> "Offline"
                    }
                    text.setTextColor(
                        when {
                            status == null -> getColor(R.color.text_secondary)
                            connected -> 0xFF15803D.toInt()
                            else -> 0xFFDC2626.toInt()
                        }
                    )
                }
            },
            onError = { }
        )

        // Feedings: the 3 newest on Home, and today's count for the meal limit (UI-8.3)
        feedingListener = cloud.listenToFeedings(FEEDINGS_TO_LOAD,
            onValue = { rows ->
                runOnUiThread {
                    mealsToday = MealLimit.mealsToday(rows)
                    showMealsToday()
                    val shown = rows.filter { it["status"]?.toString() != "scheduled" }.take(3)
                    textFeedHistory.text = if (shown.isEmpty()) "No meals recorded yet" else shown.joinToString("\n") { row ->
                        val time = (row["time"] as? Number)?.toLong() ?: 0L
                        val whenText = if (time > 0) clock(time) else "Time pending"
                        val status = row["status"]?.toString()?.replaceFirstChar { it.uppercase() } ?: "Unknown"
                        "$whenText  ·  ${portionName(row["portion"])}  ·  $status"
                    }
                }
            },
            onError = { message -> runOnUiThread { textFeeder.text = "Firebase feed history error: $message" } }
        )
    }

    /** UI-5.3: the number turns blue (low), green (normal) or red (high) for the pet's species. */
    private fun showHeartRate(bpm: Int?) {
        val number = findViewById<TextView>(R.id.textHeartRate)
        val note = findViewById<TextView>(R.id.textHeartRateNote)
        if (bpm == null || bpm <= 0) {
            number.text = "--"
            number.setTextColor(getColor(R.color.text_primary))
            note.text = "Waiting for the collar"
            return
        }
        val level = HeartRateRange.levelOf(bpm, petSpecies)
        number.text = bpm.toString()
        number.setTextColor(level.color)
        note.text = level.label + " for " + (if (petSpecies == "Cat") "a cat" else "a dog")
    }

    private fun detachCloudListeners() {
        collarListener?.let { cloud?.removeCollarListener(it) }
        feedingListener?.let { cloud?.removeFeedingsListener(FEEDINGS_TO_LOAD, it) }
        feederStatusListener?.let { cloud?.removeFeederStatusListener(it) }
        collarListener = null
        feedingListener = null
        feederStatusListener = null
        cloud = null
    }

    private fun sendFeedNowToCloud() {
        val portion = when (feedPortionGroup.checkedRadioButtonId) {
            R.id.homeFeedSmall -> 1
            R.id.homeFeedLarge -> 3
            else -> 2
        }
        // UI-8.3: stop at the daily meal limit from Settings
        val limit = SettingsStorage(this).mealLimit
        if (!MealLimit.canFeed(mealsToday, limit)) {
            textFeeder.text = "Daily limit reached ($mealsToday of $limit meals). You can change it in Settings."
            textFeeder.setTextColor(0xFFDC2626.toInt())
            return
        }
        textFeeder.setTextColor(getColor(R.color.text_secondary))

        buttonFeedNow.isEnabled = false
        textFeeder.text = "Sending Feed Now…"
        cloud?.createFeedNow(portion) { commandId, error ->
            runOnUiThread {
                // Same 3 seconds as the feeder's cooldown, so one tap = one meal
                buttonFeedNow.postDelayed({ buttonFeedNow.isEnabled = true }, FEED_LOCK_MS)
                if (error != null) {
                    textFeeder.text = "Could not send Feed Now: $error"
                    cloud?.addAlert("command_error", error)
                } else {
                    textFeeder.text = "Feed Now sent. Waiting for the feeder to confirm."
                }
            }
        } ?: run {
            buttonFeedNow.isEnabled = true
            textFeeder.text = "Firebase is not connected. Please sign in again."
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
