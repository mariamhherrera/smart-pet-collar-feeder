
package com.example.pettracker

import android.content.Context

class SessionManager(context: Context) {

    private val preferences = context.getSharedPreferences(
        "PetTrackerSession",
        Context.MODE_PRIVATE
    )

    fun login(userId: Long) {
        preferences.edit()
            .putLong("userId", userId)
            .apply()
    }

    fun getUserId(): Long {
        return preferences.getLong("userId", -1L)
    }

    fun isLoggedIn(): Boolean {
        return getUserId() != -1L
    }

    fun logout() {
        preferences.edit()
            .remove("userId")
            .commit()
    }
}
