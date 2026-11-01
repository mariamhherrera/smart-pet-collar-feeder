package com.example.pettracker.bluetooth

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Splits a byte stream into text lines. Lines end with "\n" ("\r\n" is fine too).
 * Empty lines are skipped, and a line longer than [maxLength] bytes is dropped, so garbage
 * without a line break cannot grow without limit.
 */
class LineReader(input: InputStream, private val maxLength: Int = 1024) {

    private val input = BufferedInputStream(input)
    private val buffer = ByteArray(maxLength)

    /** Blocks until a full line arrived. Returns null at the end of the stream. */
    fun readLine(): String? {
        var length = 0
        var tooLong = false

        while (true) {
            val b = input.read()
            if (b < 0) return null // the stream ended; an unfinished line is dropped

            if (b != '\n'.code) {
                if (length < maxLength) buffer[length++] = b.toByte() else tooLong = true
                continue
            }

            if (length > 0 && buffer[length - 1] == '\r'.code.toByte()) length--

            if (length > 0 && !tooLong) return String(buffer, 0, length, Charsets.UTF_8)

            length = 0
            tooLong = false
        }
    }
}
