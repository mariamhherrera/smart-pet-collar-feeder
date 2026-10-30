package com.example.pettracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PetProfile(
    val name: String,
    val species: String,
    val breed: String,
    val weight: Double,
    val age: Double,
    val health: String
)

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(
        context,
        "pettracker_firebase_profiles.db",
        null,
        1
    ) {

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE pet_profiles (
                user_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                species TEXT NOT NULL,
                breed TEXT NOT NULL,
                weight REAL NOT NULL,
                age REAL NOT NULL,
                health TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Future database upgrades
    }

    fun savePetProfile(
        userId: String,
        profile: PetProfile
    ): Boolean {

        val values = ContentValues().apply {
            put("user_id", userId)
            put("name", profile.name)
            put("species", profile.species)
            put("breed", profile.breed)
            put("weight", profile.weight)
            put("age", profile.age)
            put("health", profile.health)
        }

        return writableDatabase.insertWithOnConflict(
            "pet_profiles",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    fun getPetProfile(userId: String): PetProfile? {

        readableDatabase.query(
            "pet_profiles",
            null,
            "user_id = ?",
            arrayOf(userId),
            null,
            null,
            null
        ).use { cursor ->

            if (!cursor.moveToFirst()) {
                return null
            }

            return PetProfile(
                name = cursor.getString(
                    cursor.getColumnIndexOrThrow("name")
                ),
                species = cursor.getString(
                    cursor.getColumnIndexOrThrow("species")
                ),
                breed = cursor.getString(
                    cursor.getColumnIndexOrThrow("breed")
                ),
                weight = cursor.getDouble(
                    cursor.getColumnIndexOrThrow("weight")
                ),
                age = cursor.getDouble(
                    cursor.getColumnIndexOrThrow("age")
                ),
                health = cursor.getString(
                    cursor.getColumnIndexOrThrow("health")
                )
            )
        }
    }
}

