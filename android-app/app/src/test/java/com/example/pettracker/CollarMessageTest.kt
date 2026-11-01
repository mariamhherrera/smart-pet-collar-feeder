package com.example.pettracker

import com.example.pettracker.bluetooth.CollarMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CollarMessageTest {

    @Test
    fun parsesALineLikeTheFirmwareSendsIt() {
        // snprintf("%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f") in motion_reader.ino
        val sample = CollarMessage.parse("12.340,0.0123,-0.0456,0.9981,1.2210,-0.3050,0.0000")

        assertNotNull(sample)
        sample!!
        assertEquals(12.340, sample.timeSeconds, 1e-9)
        assertEquals(0.0123, sample.ax, 1e-9)
        assertEquals(-0.0456, sample.ay, 1e-9)
        assertEquals(0.9981, sample.az, 1e-9)
        assertEquals(1.2210, sample.gx, 1e-9)
        assertEquals(-0.3050, sample.gy, 1e-9)
        assertEquals(0.0, sample.gz, 1e-9)
    }

    @Test
    fun ignoresSpacesAndLineEndings() {
        assertNotNull(CollarMessage.parse("1.000,0,0,1,0,0,0\r"))
        assertNotNull(CollarMessage.parse(" 1.000, 0, 0, 1, 0, 0, 0 "))
    }

    @Test
    fun rejectsLinesThatAreNotACollarReading() {
        val bad = listOf(
            "",
            "time_s,x_g,y_g,z_g,gx_deg_s,gy_deg_s,gz_deg_s", // the header line
            "12.340,0.0123,-0.0456",                         // cut off
            "12.340,0.0123,-0.0456,0.9981,1.2210,-0.3050",   // one field missing
            "12.340,0.0123,-0.0456,0.9981,1.2210,-0.3050,0.0000,5", // one field too many
            "12.340,0.0123,abc,0.9981,1.2210,-0.3050,0.0000",
            "12.340,0.0123,,0.9981,1.2210,-0.3050,0.0000",
            "12.340,NaN,0,1,0,0,0",
            "12.340,0,Infinity,1,0,0,0",
            "Sensor reading failed!"
        )

        for (line in bad) {
            assertNull("Should be rejected: \"$line\"", CollarMessage.parse(line))
        }
    }
}