package com.example.pettracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

/**
 * UI-8 Settings page.
 *  - UI-8.1 layout: meals per day, low food alert, less active alert, safe zone (COL-6), account.
 *  - UI-8.2 save: on the phone (SettingsStorage) and a copy in Firebase (settings).
 *  - UI-8.3 the meal limit is checked by Feed Now on Home and on the Feeder page.
 * Log out moved here from Home.
 */
class SettingsActivity : Activity() {

    private val auth = FirebaseAuth.getInstance()
    private lateinit var settings: SettingsStorage
    private lateinit var textMealLimit: TextView
    private var mealLimit = SettingsStorage.DEFAULT_MEAL_LIMIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (auth.currentUser == null) {
            returnToLogin()
            return
        }
        setContentView(R.layout.activity_settings)
        BottomNav.setUp(this, BottomNav.SETTINGS)
        settings = SettingsStorage(this)

        // Meals per day: - and + buttons, 1 to 6
        textMealLimit = findViewById(R.id.textMealLimit)
        mealLimit = settings.mealLimit
        showMealLimit()
        findViewById<Button>(R.id.buttonMealLimitMinus).setOnClickListener {
            if (mealLimit > 1) mealLimit--
            showMealLimit()
        }
        findViewById<Button>(R.id.buttonMealLimitPlus).setOnClickListener {
            if (mealLimit < SettingsStorage.MAX_MEAL_LIMIT) mealLimit++
            showMealLimit()
        }

        // Show the saved alert settings
        val lowFoodGroup = findViewById<RadioGroup>(R.id.lowFoodGroup)
        lowFoodGroup.check(
            when (settings.lowFoodPercent) {
                10 -> R.id.lowFood10
                30 -> R.id.lowFood30
                else -> R.id.lowFood20
            }
        )
        val switchLessActive = findViewById<Switch>(R.id.switchLessActive)
        switchLessActive.isChecked = settings.lessActiveAlerts

        // Safe zone (COL-6)
        val radiusGroup = findViewById<RadioGroup>(R.id.safeRadiusGroup)
        radiusGroup.check(
            when (settings.safeRadius) {
                50 -> R.id.radius50
                200 -> R.id.radius200
                else -> R.id.radius100
            }
        )
        val switchSafeZone = findViewById<Switch>(R.id.switchSafeZone)
        switchSafeZone.isChecked = settings.safeZoneAlerts
        showHome()
        findViewById<Button>(R.id.buttonSetHome).setOnClickListener { setHomeFromCollar() }

        findViewById<Button>(R.id.buttonSaveSettings).setOnClickListener {
            save(lowFoodGroup, switchLessActive, radiusGroup, switchSafeZone)
        }

        // Account
        findViewById<TextView>(R.id.textAccountEmail).text =
            "Logged in as ${auth.currentUser?.email ?: "unknown"}"
        findViewById<Button>(R.id.buttonLogout).setOnClickListener {
            auth.signOut()
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            returnToLogin()
        }
    }

    private fun showMealLimit() {
        textMealLimit.text = mealLimit.toString()
    }

    /** "Not set yet" or the saved home coordinates. */
    private fun showHome() {
        val lat = settings.homeLatitude
        val lon = settings.homeLongitude
        findViewById<TextView>(R.id.textHomeSet).text =
            if (lat == null || lon == null) "Not set yet. Put the collar at home (outside or near a window), then tap the button."
            else "Set (%.5f, %.5f)".format(lat, lon)
    }

    /** COL-6: saves the collar's current GPS position as home. */
    private fun setHomeFromCollar() {
        val uid = auth.currentUser?.uid ?: return
        PetCloudRepository(uid).loadLatestCollar(
            onValue = { row ->
                runOnUiThread {
                    val reading = CollarReading.fromMap(row)
                    if (reading == null || !reading.hasLocation()) {
                        Toast.makeText(this, "The collar has no GPS position yet. Try outside or near a window.",
                            Toast.LENGTH_LONG).show()
                    } else {
                        settings.setHome(reading.latitude!!, reading.longitude!!)
                        showHome()
                        Toast.makeText(this, "Home saved. Tap Save settings to keep a copy online.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onError = { message -> runOnUiThread { Toast.makeText(this, "Firebase error: $message", Toast.LENGTH_LONG).show() } }
        )
    }

    /** UI-8.2: save on the phone first (works offline), then a copy in Firebase. */
    private fun save(lowFoodGroup: RadioGroup, switchLessActive: Switch,
                     radiusGroup: RadioGroup, switchSafeZone: Switch) {
        settings.mealLimit = mealLimit
        settings.lowFoodPercent = when (lowFoodGroup.checkedRadioButtonId) {
            R.id.lowFood10 -> 10
            R.id.lowFood30 -> 30
            else -> 20
        }
        settings.lessActiveAlerts = switchLessActive.isChecked
        settings.safeRadius = when (radiusGroup.checkedRadioButtonId) {
            R.id.radius50 -> 50
            R.id.radius200 -> 200
            else -> 100
        }
        settings.safeZoneAlerts = switchSafeZone.isChecked

        val saved = findViewById<TextView>(R.id.textSettingsSaved)
        saved.text = "Saved on this phone"
        val uid = auth.currentUser?.uid ?: return
        PetCloudRepository(uid).saveSettings(settings.toMap()) { error ->
            runOnUiThread {
                saved.text = if (error == null) "Settings saved" else "Saved on this phone only ($error)"
            }
        }
    }

    private fun returnToLogin() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
