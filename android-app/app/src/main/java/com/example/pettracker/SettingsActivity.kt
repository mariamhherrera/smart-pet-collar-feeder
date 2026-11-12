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
 *  - UI-8.1 layout: meals per day, low food alert, heart rate alerts, account.
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
        val switchHeart = findViewById<Switch>(R.id.switchHeartAlerts)
        switchHeart.isChecked = settings.heartRateAlerts

        findViewById<Button>(R.id.buttonSaveSettings).setOnClickListener {
            save(lowFoodGroup, switchHeart)
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

    /** UI-8.2: save on the phone first (works offline), then a copy in Firebase. */
    private fun save(lowFoodGroup: RadioGroup, switchHeart: Switch) {
        settings.mealLimit = mealLimit
        settings.lowFoodPercent = when (lowFoodGroup.checkedRadioButtonId) {
            R.id.lowFood10 -> 10
            R.id.lowFood30 -> 30
            else -> 20
        }
        settings.heartRateAlerts = switchHeart.isChecked

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
