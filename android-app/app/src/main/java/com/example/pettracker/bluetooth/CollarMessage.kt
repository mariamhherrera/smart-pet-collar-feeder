package com.example.pettracker.bluetooth

/** One reading from the collar. Units: acceleration in g, rotation in degrees per second. */
data class MotionSample(
    val timeSeconds: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double
)

/**
 * The message the collar sends, as written by firmware/collar/motion_reader/motion_reader.ino:
 *
 *     time_s,x_g,y_g,z_g,gx_deg_s,gy_deg_s,gz_deg_s
 *     12.340,0.0123,-0.0456,0.9981,1.2210,-0.3050,0.0000
 *
 * One line per reading, 50 per second. See docs/bluetooth-messages.md.
 */
object CollarMessage {

    /** The Bluetooth name the collar firmware gives itself (SerialBT.begin in the .ino). */
    const val DEVICE_NAME = "PetCollar_ESP32"

    private const val FIELD_COUNT = 7

    /** Returns null when the line is not a valid collar reading. */
    fun parse(line: String): MotionSample? {
        val fields = line.trim().split(',')
        if (fields.size != FIELD_COUNT) return null

        val v = DoubleArray(FIELD_COUNT)
        for (i in fields.indices) {
            val value = fields[i].trim().toDoubleOrNull() ?: return null
            if (!value.isFinite()) return null
            v[i] = value
        }

        return MotionSample(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }
}