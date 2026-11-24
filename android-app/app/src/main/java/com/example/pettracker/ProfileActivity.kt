package com.example.pettracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * UI-7.2 Pet profile, and the collar linked to the pet (COM-4).
 * Right after registering it is step 1 of 2 ([EXTRA_SETUP]): saving opens Pair your collar.
 */
class ProfileActivity : Activity() {

    companion object {
        const val EXTRA_SETUP = "setup"
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseHelper
    private var setup = false
    private var collarLink: CollarLink? = null
    private var collarWatch: Pair<Query, ValueEventListener>? = null

    private lateinit var editName: EditText
    private lateinit var spinnerSpecies: Spinner
    private lateinit var editBreed: EditText
    private lateinit var editWeight: EditText
    private lateinit var editAge: EditText
    private lateinit var editHealth: EditText
    private var setupMealLimit = SettingsStorage.DEFAULT_MEAL_LIMIT   // first setup only

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        database = DatabaseHelper(this)

        if (auth.currentUser == null) {
            returnToLogin()
            return
        }

        setContentView(R.layout.activity_profile)
        // Opened from the profile icon: back arrow in the top bar (UI-7.1)
        setup = intent.getBooleanExtra(EXTRA_SETUP, false) || database.getPetProfile(auth.currentUser!!.uid) == null
        BottomNav.setUpDetail(this, if (setup) "Add your pet" else "Pet profile")
        findViewById<View>(R.id.textProfileStep).visibility = if (setup) View.VISIBLE else View.GONE

        // COM-4: the collar card. Pairing is step 2 in setup, so it is hidden there.
        collarLink = CollarLink(this, auth.currentUser!!.uid)
        findViewById<View>(R.id.cardCollar).visibility = if (setup) View.GONE else View.VISIBLE
        findViewById<View>(R.id.buttonPairCollar).setOnClickListener { openPairing() }
        findViewById<View>(R.id.buttonChangeCollar).setOnClickListener { openPairing() }
        findViewById<View>(R.id.buttonUnlinkCollar).setOnClickListener { confirmUnlink() }

        editName = findViewById(R.id.editPetName)
        spinnerSpecies = findViewById(R.id.spinnerSpecies)
        editBreed = findViewById(R.id.editBreed)
        editWeight = findViewById(R.id.editWeight)
        editAge = findViewById(R.id.editAge)
        editHealth = findViewById(R.id.editHealth)

        val buttonSave =
            findViewById<Button>(R.id.buttonSaveProfile)

        loadProfile()
        // The profile may have been changed on another phone: show the account's copy
        PetProfileSync.sync(this, auth.currentUser!!.uid) { runOnUiThread { if (!isFinishing) loadProfile() } }

        buttonSave.setOnClickListener {
            saveProfile()
        }

        // First setup only: meals per day, the same limit as in Settings (changed there later)
        if (setup) setUpMealLimit()

        // Log out, same as in Settings
        findViewById<Button>(R.id.buttonProfileLogout).setOnClickListener {
            auth.signOut()
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            returnToLogin()
        }

    }

    override fun onResume() {
        super.onResume()

        if (::auth.isInitialized && auth.currentUser == null) {
            returnToLogin()
            return
        }
        if (setup) return

        // Show the phone's copy right away, then check Firebase (it may have changed on another phone)
        showCollar(collarLink?.collarId)
        collarLink?.refresh { id -> runOnUiThread { showCollar(id) } }
    }

    override fun onPause() {
        super.onPause()
        stopWatchingCollar()
    }

    // ---------- Collar (COM-4) ----------

    private fun petName(): String =
        editName.text.toString().trim().ifEmpty { null }
            ?: database.getPetProfile(auth.currentUser?.uid ?: "")?.name
            ?: "your pet"

    /** "PC-3C71F2 · Linked to Max · Online", with Change collar and Unlink. Or "No collar linked". */
    private fun showCollar(id: String?) {
        stopWatchingCollar()
        findViewById<View>(R.id.layoutCollarLinked).visibility = if (id != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutNoCollar).visibility = if (id == null) View.VISIBLE else View.GONE
        if (id == null) return

        findViewById<TextView>(R.id.textProfileCollarId).text = id
        showCollarStatus(null)
        collarWatch = collarLink?.listenToCollar(id) { reading -> runOnUiThread { showCollarStatus(reading) } }
    }

    private fun showCollarStatus(reading: CollarReading?) {
        val online = CollarPairing.isOnline(reading?.timeMillis, System.currentTimeMillis())
        val status = findViewById<TextView>(R.id.textProfileCollarStatus)
        status.text = "Linked to ${petName()} · " + if (online) "Online" else "Offline"
        status.setTextColor(getColor(if (online) R.color.primary else R.color.text_secondary))
    }

    private fun stopWatchingCollar() {
        collarWatch?.let { (query, listener) -> query.removeEventListener(listener) }
        collarWatch = null
    }

    private fun openPairing() {
        startActivity(Intent(this, PairCollarActivity::class.java))
    }
    private var currentDialog: AlertDialog? = null

    private fun confirmUnlink() {
        val id = collarLink?.collarId ?: return
        val name = petName()
        showStyledDialog(
            title = "Unpair $id?",
            message = "Readings from this collar will stop showing for $name. You can pair it again later.",
            confirmText = "Unpair",
            showInput = false
        ) {
            collarLink?.unlink { error ->
                runOnUiThread {
                    if (error != null) {
                        showDialogError(error)
                    } else {
                        currentDialog?.dismiss()
                        Toast.makeText(this, "Collar unpaired", Toast.LENGTH_SHORT).show()
                        showCollar(null)
                    }
                }
            }
        }
    }

    private fun showStyledDialog(
        title: String,
        message: String,
        confirmText: String,
        showInput: Boolean,
        onConfirm: () -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_pair_feeder, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text = message
        val input = view.findViewById<EditText>(R.id.dialogInput)
        if (!showInput) input.visibility = View.GONE
        val confirm = view.findViewById<Button>(R.id.dialogConfirm)
        confirm.text = confirmText

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        view.findViewById<Button>(R.id.dialogCancel).setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener { onConfirm() }
        currentDialog = dialog
        dialog.show()
    }

    private fun showDialogError(message: String) {
        currentDialog?.let { d ->
            val err = d.findViewById<TextView>(R.id.dialogError)
            if (err != null) {
                err.text = message
                err.visibility = View.VISIBLE
                return
            }
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Shows the Meals per day card with its - and + buttons (1 to 6). */
    private fun setUpMealLimit() {
        setupMealLimit = SettingsStorage(this).mealLimit
        val text = findViewById<TextView>(R.id.textSetupMealLimit)
        text.text = setupMealLimit.toString()
        findViewById<View>(R.id.cardSetupMealLimit).visibility = View.VISIBLE
        findViewById<Button>(R.id.buttonSetupMealMinus).setOnClickListener {
            if (setupMealLimit > 1) setupMealLimit--
            text.text = setupMealLimit.toString()
        }
        findViewById<Button>(R.id.buttonSetupMealPlus).setOnClickListener {
            if (setupMealLimit < SettingsStorage.MAX_MEAL_LIMIT) setupMealLimit++
            text.text = setupMealLimit.toString()
        }
    }

    /** Saves the first-setup meal limit like Settings does: on the phone, then a copy in Firebase. */
    private fun saveSetupMealLimit(uid: String) {
        val settings = SettingsStorage(this)
        settings.mealLimit = setupMealLimit
        PetCloudRepository(uid).saveSettings(settings.toMap()) { }
    }

    private fun loadProfile() {

        val uid = auth.currentUser?.uid ?: return

        val profile = database.getPetProfile(uid)
            ?: return

        editName.setText(profile.name)
        editBreed.setText(profile.breed)
        editWeight.setText(profile.weight.toString())
        editAge.setText(profile.age.toString())
        editHealth.setText(profile.health)

        val adapter = spinnerSpecies.adapter

        for (i in 0 until adapter.count) {

            if (adapter.getItem(i).toString() == profile.species) {
                spinnerSpecies.setSelection(i)
                break
            }
        }
    }

    private fun saveProfile() {

        val uid = auth.currentUser?.uid

        if (uid == null) {
            returnToLogin()
            return
        }

        val name = editName.text.toString().trim()
        val species = spinnerSpecies.selectedItem.toString()
        val breed = editBreed.text.toString().trim()

        val weight = editWeight.text.toString()
            .trim().toDoubleOrNull()

        val age = editAge.text.toString()
            .trim().toDoubleOrNull()

        val health = editHealth.text.toString().trim()

        if (name.isEmpty()) {
            editName.error = "Enter pet name"
            return
        }

        if (species == "Select species") {
            Toast.makeText(
                this,
                "Select Dog or Cat",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (breed.isEmpty()) {
            editBreed.error = "Enter breed"
            return
        }

        if (weight == null || !weight.isFinite() || weight <= 0.0) {
            editWeight.error = "Enter a valid weight"
            return
        }

        if (age == null || !age.isFinite() || age < 0.0) {
            editAge.error = "Enter a valid age"
            return
        }

        if (health.isEmpty()) {
            editHealth.error = "Enter conditions or None"
            return
        }

        val profile = PetProfile(
            name = name,
            species = species,
            breed = breed,
            weight = weight,
            age = age,
            health = health
        )

        val saved = database.savePetProfile(uid, profile)

        if (saved) {
            // Same profile on every phone logged into this account
            PetCloudRepository(uid).savePetProfile(profile)

            Toast.makeText(
                this,
                "Pet profile saved",
                Toast.LENGTH_SHORT
            ).show()

            // Right after registering: save the meal limit, then step 2, pair the collar (COM-4)
            if (setup) {
                saveSetupMealLimit(uid)
                startActivity(
                    Intent(this, PairCollarActivity::class.java)
                        .putExtra(PairCollarActivity.EXTRA_SETUP, true)
                )
            }

            finish()

        } else {

            Toast.makeText(
                this,
                "Could not save profile",
                Toast.LENGTH_LONG
            ).show()
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

