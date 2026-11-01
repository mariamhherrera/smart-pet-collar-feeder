package com.example.pettracker

import com.example.pettracker.bluetooth.LineReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class LineReaderTest {

    private fun reader(text: String, maxLength: Int = 1024) =
        LineReader(ByteArrayInputStream(text.toByteArray()), maxLength)

    @Test
    fun splitsLinesEndingWithLfOrCrLf() {
        val reader = reader("one\ntwo\r\nthree\n")

        assertEquals("one", reader.readLine())
        assertEquals("two", reader.readLine())
        assertEquals("three", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun skipsEmptyLines() {
        val reader = reader("\n\r\none\n\n\ntwo\n")

        assertEquals("one", reader.readLine())
        assertEquals("two", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun dropsAnUnfinishedLastLine() {
        val reader = reader("complete\nunfinis")

        assertEquals("complete", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun dropsALineThatIsTooLongAndKeepsGoing() {
        val reader = reader("short\n" + "x".repeat(50) + "\nafter\n", maxLength = 10)

        assertEquals("short", reader.readLine())
        assertEquals("after", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun acceptsALineOfExactlyTheMaximumLength() {
        val reader = reader("0123456789\n", maxLength = 10)

        assertEquals("0123456789", reader.readLine())
    }
}