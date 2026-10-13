package com.swift.browser.extensionengine

/** Security-only JSON parser: rejects duplicate object keys and excessive nesting. */
object StrictManifestJsonValidator {
    private const val MAX_DEPTH = 128
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

    fun validate(json: String) {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
            "manifest.json exceeds ${MAX_MANIFEST_BYTES} bytes"
        }
        Parser(json).parseDocument()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument() {
            skipWs(); parseValue(0); skipWs()
            if (i != s.length) fail("Trailing data after JSON document")
        }

        private fun parseValue(depth: Int) {
            if (depth > MAX_DEPTH) fail("JSON nesting exceeds $MAX_DEPTH levels")
            skipWs()
            if (i >= s.length) fail("Unexpected end of JSON")
            when (s[i]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Unexpected token at offset $i")
            }
        }

        private fun parseObject(depth: Int) {
            expect('{'); skipWs()
            if (peek('}')) { i++; return }
            val keys = HashSet<String>()
            while (true) {
                skipWs(); if (!peek('"')) fail("Object member name must be a string")
                val key = parseString()
                if (!keys.add(key)) fail("Duplicate manifest key: $key")
                skipWs(); expect(':'); parseValue(depth); skipWs()
                when {
                    peek('}') -> { i++; return }
                    peek(',') -> i++
                    else -> fail("Expected ',' or '}' at offset $i")
                }
            }
        }

        private fun parseArray(depth: Int) {
            expect('['); skipWs()
            if (peek(']')) { i++; return }
            while (true) {
                parseValue(depth); skipWs()
                when {
                    peek(']') -> { i++; return }
                    peek(',') -> i++
                    else -> fail("Expected ',' or ']' at offset $i")
                }
            }
        }

        private fun parseString(): String {
            expect('"'); val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c.code < 0x20 -> fail("Unescaped control character in JSON string")
                    c != '\\' -> out.append(c)
                    i >= s.length -> fail("Unterminated escape sequence")
                    else -> when (val e = s[i++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("Incomplete unicode escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("Invalid unicode escape")
                            out.append(code.toChar()); i += 4
                        }
                        else -> fail("Invalid JSON escape: \\$e")
                    }
                }
            }
            fail("Unterminated JSON string")
        }

        private fun parseLiteral(literal: String) {
            if (!s.regionMatches(i, literal, 0, literal.length)) fail("Invalid literal")
            i += literal.length
        }

        private fun parseNumber() {
            val start = i
            if (peek('-')) i++
            if (i >= s.length) fail("Invalid number")
            if (s[i] == '0') i++ else {
                if (s[i] !in '1'..'9') fail("Invalid number")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (peek('.')) {
                i++; if (i >= s.length || !s[i].isDigit()) fail("Invalid number fraction")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++; if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) fail("Invalid number exponent")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (start == i) fail("Invalid number")
        }

        private fun skipWs() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        private fun expect(c: Char) { if (i >= s.length || s[i] != c) fail("Expected '$c' at offset $i"); i++ }
        private fun peek(c: Char): Boolean = i < s.length && s[i] == c
        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
