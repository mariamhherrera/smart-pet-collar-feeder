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

    private fun openHome() {

        val intent = Intent(
            this,
            HomeActivity::class.java
        )

        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK

        startActivity(intent)
        finish()
    }
}

