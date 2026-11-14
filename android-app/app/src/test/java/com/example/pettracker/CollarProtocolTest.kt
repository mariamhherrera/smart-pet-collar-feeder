package com.example.pettracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollarProtocolTest {
    @Test fun parsesFirmwareCsv() {
        val parsed = CollarProtocol.parseMotion("1.250,0.1000,0.2000,0.3000,1.0000,2.0000,3.0000")
        requireNotNull(parsed)
        assertEquals(1.25, parsed.timeSeconds, 0.0001)
        assertEquals(0.1, parsed.ax, 0.0001)
        assertEquals(3.0, parsed.gz, 0.0001)
    }

    @Test fun parsesOptionalHeartRate() {
        val parsed = CollarProtocol.parseMotion("1.250,0.1,0.2,0.3,1.0,2.0,3.0,92")
        requireNotNull(parsed)
        assertEquals(92, parsed.heartRateBpm)
    }

    @Test fun ignoresHeaderAndInvalidLines() {
        assertNull(CollarProtocol.parseMotion("time_s,x_g,y_g,z_g,gx_deg_s,gy_deg_s,gz_deg_s"))
        assertNull(CollarProtocol.parseMotion("Sensor reading failed!"))
    }
}
