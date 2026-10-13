package com.swift.browser.extensionengine

/** Canonical Chromium-compatible base-16-nibble extension id encoding. */
object ExtensionIdCodec {
    private const val ALPHABET = "abcdefghijklmnop"

    fun encodeNibblesToAlphabet(bytes: ByteArray, byteCount: Int = 16): String {
        require(byteCount >= 0) { "byteCount must not be negative" }
        val limit = minOf(byteCount, bytes.size)
        val result = StringBuilder(limit * 2)
        for (i in 0 until limit) {
            val value = bytes[i].toInt() and 0xFF
            result.append(ALPHABET[(value ushr 4) and 0x0F])
            result.append(ALPHABET[value and 0x0F])
        }
        return result.toString()
    }
}
