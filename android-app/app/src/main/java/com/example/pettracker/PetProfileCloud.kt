package com.example.pettracker

/**
 * The pet profile as it is saved in Firebase, at owners/<uid>/pet/profile, so every phone
 * logged into the same account shows the same pet (name, breed, weight...).
 * Before this, each phone only had its own copy (DatabaseHelper), so two phones could show two different names.
 */
object PetProfileCloud {

    fun toMap(profile: PetProfile): Map<String, Any> = mapOf(
        "name" to profile.name,
        "species" to profile.species,
        "breed" to profile.breed,
        "weight" to profile.weight,
        "age" to profile.age,
        "health" to profile.health
    )

    /** Null if nothing is saved online yet (or the name is missing). */
    fun fromMap(map: Map<*, *>?): PetProfile? {
        if (map == null) return null
        val name = map["name"] as? String ?: return null
        return PetProfile(
            name = name,
            species = map["species"] as? String ?: "",
            breed = map["breed"] as? String ?: "",
            weight = CollarReading.number(map["weight"]) ?: 0.0,
            age = CollarReading.number(map["age"]) ?: 0.0,
            health = map["health"] as? String ?: ""
        )
    }
}
