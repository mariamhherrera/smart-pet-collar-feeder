package com.example.pettracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class ProfileActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseHelper

    private lateinit var editName: EditText
    private lateinit var spinnerSpecies: Spinner
    private lateinit var editBreed: EditText
    private lateinit var editWeight: EditText
    private lateinit var editAge: EditText
    private lateinit var editHealth: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        database = DatabaseHelper(this)

        if (auth.currentUser == null) {
            returnToLogin()
            return
        }

        setContentView(R.layout.activity_profile)

        editName = findViewById(R.id.editPetName)
        spinnerSpecies = findViewById(R.id.spinnerSpecies)
        editBreed = findViewById(R.id.editBreed)
        editWeight = findViewById(R.id.editWeight)
        editAge = findViewById(R.id.editAge)
        editHealth = findViewById(R.id.editHealth)

        val buttonSave =
            findViewById<Button>(R.id.buttonSaveProfile)

        val buttonBack =
            findViewById<Button>(R.id.buttonBackHome)

        loadProfile()

        buttonSave.setOnClickListener {
            saveProfile()
        }

        buttonBack.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()

        if (::auth.isInitialized && auth.currentUser == null) {
            returnToLogin()
        }
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

            Toast.makeText(
                this,
                "Pet profile saved",
                Toast.LENGTH_SHORT
            ).show()

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

