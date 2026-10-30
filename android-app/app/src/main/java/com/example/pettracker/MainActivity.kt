package com.example.pettracker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class MainActivity : Activity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()

        // Skip login if a user is already logged in
        if (auth.currentUser != null) {
            openHome()
            return
        }

        setContentView(R.layout.activity_main)

        val editEmail =
            findViewById<EditText>(R.id.editLoginEmail)

        val editPassword =
            findViewById<EditText>(R.id.editLoginPassword)

        val buttonLogin =
            findViewById<Button>(R.id.buttonLogin)

        val buttonRegister =
            findViewById<Button>(R.id.buttonGoRegister)

        buttonLogin.setOnClickListener {

            val email = editEmail.text.toString().trim()
            val password = editPassword.text.toString()

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                editEmail.error = "Enter a valid email"
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                editPassword.error = "Enter your password"
                return@setOnClickListener
            }

            buttonLogin.isEnabled = false

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->

                    buttonLogin.isEnabled = true

                    if (task.isSuccessful) {

                        Toast.makeText(
                            this,
                            "Login successful",
                            Toast.LENGTH_SHORT
                        ).show()

                        openHome()

                    } else {

                        Toast.makeText(
                            this,
                            "Login failed: " +
                                    (task.exception?.localizedMessage
                                        ?: "Check your credentials"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        buttonRegister.setOnClickListener {
            startActivity(
                Intent(this, RegisterActivity::class.java)
            )
        }
    }

    override fun onResume() {
        super.onResume()

        if (::auth.isInitialized &&
            auth.currentUser != null &&
            !isFinishing) {
            openHome()
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
