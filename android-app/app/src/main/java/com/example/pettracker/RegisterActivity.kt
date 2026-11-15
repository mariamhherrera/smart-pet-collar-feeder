package com.example.pettracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class RegisterActivity : Activity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()

        setContentView(R.layout.activity_register)

        val editEmail =
            findViewById<EditText>(R.id.editRegisterEmail)

        val editPassword =
            findViewById<EditText>(R.id.editRegisterPassword)

        val editConfirm =
            findViewById<EditText>(R.id.editConfirmPassword)

        val buttonCreate =
            findViewById<Button>(R.id.buttonCreateAccount)

        val buttonBack =
            findViewById<Button>(R.id.buttonBackToLogin)

        buttonCreate.setOnClickListener {

            val email = editEmail.text.toString().trim()
            val password = editPassword.text.toString()
            val confirm = editConfirm.text.toString()

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                editEmail.error = "Enter a valid email"
                return@setOnClickListener
            }

            if (password.length < 8) {
                editPassword.error = "Use at least 8 characters"
                return@setOnClickListener
            }

            if (password != confirm) {
                editConfirm.error = "Passwords do not match"
                return@setOnClickListener
            }

            buttonCreate.isEnabled = false

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->

                    buttonCreate.isEnabled = true

                    if (task.isSuccessful) {

                        Toast.makeText(
                            this,
                            "Account created successfully!",
                            Toast.LENGTH_SHORT
                        ).show()

                        openHome()

                    } else {

                        Toast.makeText(
                            this,
                            "Registration failed: " +
                                    (task.exception?.localizedMessage
                                        ?: "Unknown error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        buttonBack.setOnClickListener {
            finish()
        }
    }

    /**
     * Opens Home with the pet profile on top of it (step 1 of 2).
     * Saving the profile opens Pair your collar (step 2, COM-4).
     * Back or "Skip for now" lands on Home.
     */
    private fun openHome() {

        val home = Intent(
            this,
            HomeActivity::class.java
        )

        home.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK

        val profile = Intent(this, ProfileActivity::class.java)
            .putExtra(ProfileActivity.EXTRA_SETUP, true)

        startActivities(arrayOf(home, profile))
        finish()
    }
}

