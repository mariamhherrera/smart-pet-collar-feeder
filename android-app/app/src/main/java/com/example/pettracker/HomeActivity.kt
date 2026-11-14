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
            showLocationAndAlerts()
            clockHandler.postDelayed(this, 10_000L)
        }
    }

    // UI-8.3: meals given today, from the feeding history in Firebase
    private var mealsToday = 0
    private var lastLocation: CollarReading? = null // newest reading that had a GPS position

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
        if (hasBluetoothPermissions()) startFeeder() // Bluetooth backup for Feed Now
    }

    /** True if Bluetooth may be used. The Collar page asks for it; Home only checks. */
    private fun hasBluetoothPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
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
        if (feederLink != null || !hasBluetoothPermissions()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return // Firebase still works without it
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

    /** Called only when the Firebase Feed Now write fails: sends Feed Now by Bluetooth instead. */
    private fun feedNow(portion: Int) {
        val link = feederLink ?: run {
            setFeedNowEnabled(true)
            showFeederStatus("Could not reach the feeder (Firebase and Bluetooth backup are off)", false)
            return
        }
        setFeedNowEnabled(false)              // one tap = one command, no double taps
        showFeederStatus("Firebase unavailable · sending by Bluetooth backup…", true)

        feederSender.execute {
            val sent = link.send(FeederMessage.feedCommand(portion))
            runOnUiThread {
                if (!sent) showFeederStatus("Could not reach the feeder", false)
                // Same 3 seconds as the feeder's cooldown, then the button works again
                buttonFeedNow.postDelayed({ setFeedNowEnabled(true) }, FEED_LOCK_MS)
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
                // Firebase sends the schedule now; Bluetooth only gives the feeder its clock and meal limit
                if (feederConnected) sendTimeAndLimit()
            }
        }

        override fun onLine(line: String) {
            runOnUiThread {
                if (number != feederConnectionNumber) return@runOnUiThread
                // Turn the feeder's reply (like "OK FED 1") into a sentence for the owner
                val reply = FeederMessage.parse(line) ?: return@runOnUiThread // not a reply, ignore it
                // Answers to the automatic TIME and SCHEDULE: keep showing "Feeder connected"
                if (reply is FeederReply.TimeSet || reply is FeederReply.ScheduleSaved ||
                    reply is FeederReply.LimitSet) return@runOnUiThread
                val good = reply is FeederReply.Fed || reply is FeederReply.ScheduledFed
                showFeederStatus(FeederMessage.screenText(reply), good)
            }
        }
    }

    /**
     * Over the Bluetooth backup: gives the feeder the current time and the meal limit (UI-8.3).
     * The feeder forgets the time when it restarts, and needs the limit for scheduled meals.
     */
    private fun sendTimeAndLimit() {
        val link = feederLink ?: return
        val limit = FeederMessage.limitCommand(SettingsStorage(this).mealLimit)
        feederSender.execute {
            link.send(FeederMessage.timeCommandNow())
            link.send(limit)
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
        // UI-8.3: a warning once the limit is reached (the feeder skips scheduled meals too)
        val reached = !MealLimit.canFeed(mealsToday, limit)
        textMealsToday.text = if (reached) "Limit reached ($mealsToday of $limit)" else "$mealsToday of $limit meals today"
        textMealsToday.setTextColor(if (reached) 0xFFDC2626.toInt() else getColor(R.color.text_secondary))
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

        // Collar: newest reading (live motion, location), also saved in the history (UI-6.1)
        collarListener = cloud.listenToLatestCollar(
            onValue = { row ->
                runOnUiThread {
                    val reading = CollarReading.fromMap(row) ?: return@runOnUiThread
                    lastCollarReadingTime = if (reading.timeMillis > 0) reading.timeMillis else System.currentTimeMillis()
                    showCollarOnline()
                    cloud.saveReadingToHistory(reading)

                    textActivity.text = "X %.2f · Y %.2f · Z %.2f g".format(reading.ax, reading.ay, reading.az)
                    findViewById<TextView>(R.id.textActivityTime).text = "Updated " + clock(lastCollarReadingTime)
                    if (reading.hasLocation()) lastLocation = reading
                    showLocationAndAlerts()
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

    /**
     * Location card (COL-6) and the alert box at the top of Home.
     * Alerts: the pet left the safe zone (if turned on in Settings), or the collar went offline.
     */
    private fun showLocationAndAlerts() {
        val settings = SettingsStorage(this)
        val place = findViewById<TextView>(R.id.textHomeLocation)
        val note = findViewById<TextView>(R.id.textHomeLocationNote)
        val spot = lastLocation
        var outside = false

        when (SafeZone.status(spot, settings.homeLatitude, settings.homeLongitude, settings.safeRadius)) {
            SafeZone.Status.NO_GPS -> {
                place.text = "No GPS yet"
                place.setTextColor(getColor(R.color.text_primary))
                note.text = "GPS works outside or near a window"
            }
            SafeZone.Status.NO_HOME -> {
                place.text = "Found"
                place.setTextColor(getColor(R.color.text_primary))
                note.text = "Set home in Settings"
            }
            else -> {
                val meters = SafeZone.distanceMeters(settings.homeLatitude!!, settings.homeLongitude!!,
                    spot!!.latitude!!, spot.longitude!!)
                outside = meters > settings.safeRadius
                place.text = if (outside) "Away" else "At home"
                place.setTextColor(if (outside) 0xFFDC2626.toInt() else 0xFF15803D.toInt())
                note.text = SafeZone.formatDistance(meters) + " from home"
            }
        }

        // Alert box: only shown when something needs the owner's attention
        val alerts = mutableListOf<String>()
        val petName = findViewById<TextView>(R.id.textPetName).text.toString()
            .removePrefix("Hi, ").removeSuffix("!").takeIf { it != "Add your pet" } ?: "Your pet"
        if (outside && settings.safeZoneAlerts) alerts.add("$petName left the safe zone. Check the Collar page.")
        if (lastCollarReadingTime > 0 && System.currentTimeMillis() - lastCollarReadingTime > 30_000L)
            alerts.add("The collar is offline since " + clock(lastCollarReadingTime) + ".")
        val alertBox = findViewById<TextView>(R.id.textAlert)
        alertBox.text = alerts.joinToString("\n")
        alertBox.visibility = if (alerts.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
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

        setFeedNowEnabled(false)
        textFeeder.text = "Sending Feed Now…"
        cloud?.createFeedNow(portion) { _, error ->
            runOnUiThread {
                if (error != null) {
                    cloud?.addAlert("command_error", error)
                    if (feederConnected) feedNow(portion) else {
                        setFeedNowEnabled(true)
                        showFeederStatus("Could not send Feed Now: $error. Bluetooth backup is off.", false)
                    }
                } else {
                    // Same 3 seconds as the feeder's cooldown, so one tap = one meal
                    buttonFeedNow.postDelayed({ setFeedNowEnabled(true) }, FEED_LOCK_MS)
                    textFeeder.text = "Feed Now sent. Waiting for the feeder to confirm."
                }
            }
        } ?: run {
            if (feederConnected) feedNow(portion) else {
                setFeedNowEnabled(true)
                showFeederStatus("Firebase is not connected and Bluetooth backup is off.", false)
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
