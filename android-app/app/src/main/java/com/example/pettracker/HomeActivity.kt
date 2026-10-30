package com.example.pettracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class HomeActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        database = DatabaseHelper(this)

        if (auth.currentUser == null) {
            returnToLogin()
            return
        }

        setContentView(R.layout.activity_home)

        val buttonProfile =
            findViewById<Button>(R.id.buttonProfile)

        val buttonLogout =
            findViewById<Button>(R.id.buttonLogout)

        buttonProfile.setOnClickListener {

            startActivity(
                Intent(this, ProfileActivity::class.java)
            )
        }

        buttonLogout.setOnClickListener {

            // Logout from Firebase
            auth.signOut()

            Toast.makeText(
                this,
                "Logged out successfully",
                Toast.LENGTH_SHORT
            ).show()

            returnToLogin()
        }
    }

    override fun onResume() {
        super.onResume()

        if (!::auth.isInitialized) return

        if (auth.currentUser == null) {
            returnToLogin()
            return
        }

        showPetInformation()
    }

    private fun showPetInformation() {

        val user = auth.currentUser ?: return

        val profile = database.getPetProfile(user.uid)

        findViewById<TextView>(R.id.textWelcome).text =
            "Welcome, ${user.email ?: "User"}"

        findViewById<TextView>(R.id.textPetName).text =
            profile?.name ?: "No pet profile yet"

        findViewById<TextView>(R.id.textConnection).text =
            "Disconnected"

        findViewById<TextView>(R.id.textActivity).text =
            "No data yet"
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




