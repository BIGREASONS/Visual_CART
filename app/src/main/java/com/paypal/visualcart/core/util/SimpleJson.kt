package com.paypal.visualcart.core.util

/**
 * Pure-Kotlin JSON parser and serializer that works identically in Android ART
 * and pure-JVM unit tests (where Android's org.json stub returns defaults).
 */
object SimpleJson {

    fun parse(jsonStr: String): Any? {
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty()) return null
        return Parser(trimmed).parseValue()
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(jsonStr: String): Map<String, Any?>? {
        return parse(jsonStr) as? Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    fun parseArray(jsonStr: String): List<Any?>? {
        return parse(jsonStr) as? List<Any?>
    }

    fun escape(input: String): String {
        val sb = StringBuilder(input.length + 16)
        for (c in input) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c < ' ') {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        is String -> "\"${escape(value)}\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${escape(k.toString())}\":${encode(v)}"
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> "\"${escape(value.toString())}\""
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (i >= s.length) return null
            return when (val ch = s[i]) {
                '{' -> parseObjectInternal()
                '[' -> parseArrayInternal()
                '"' -> parseStringInternal()
                't', 'f' -> parseBooleanInternal()
                'n' -> parseNullInternal()
                '-', in '0'..'9' -> parseNumberInternal()
                else -> throw IllegalArgumentException("Unexpected JSON char '$ch' at index $i")
            }
        }

        private fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun parseObjectInternal(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // skip '{'
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (i < s.length) {
                skipWhitespace()
                if (i >= s.length || s[i] != '"') {
                    throw IllegalArgumentException("Expected '\"' key at index $i")
                }
                val key = parseStringInternal()
                skipWhitespace()
                if (i >= s.length || s[i] != ':') {
                    throw IllegalArgumentException("Expected ':' after key '$key' at index $i")
                }
                i++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (i < s.length && s[i] == ',') {
                    i++
                } else if (i < s.length && s[i] == '}') {
                    i++
                    return map
                } else {
                    break
                }
            }
            return map
        }

        private fun parseArrayInternal(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // skip '['
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (i < s.length) {
                list.add(parseValue())
                skipWhitespace()
                if (i < s.length && s[i] == ',') {
                    i++
                } else if (i < s.length && s[i] == ']') {
                    i++
                    return list
                } else {
                    break
                }
            }
            return list
        }

        private fun parseStringInternal(): String {
            i++ // skip opening '"'
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val esc = s[i++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    val code = s.substring(i, i + 4).toIntOrNull(16)
                                    if (code != null) sb.append(code.toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumberInternal(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) {
                i++
            }
            val raw = s.substring(start, i)
            return if (raw.contains('.') || raw.contains('e', ignoreCase = true)) {
                raw.toDoubleOrNull() ?: 0.0
            } else {
                raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: 0L
            }
        }

        private fun parseBooleanInternal(): Boolean {
            return if (s.startsWith("true", i)) {
                i += 4
                true
            } else if (s.startsWith("false", i)) {
                i += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean at index $i")
            }
        }

        private fun parseNullInternal(): Any? {
            if (s.startsWith("null", i)) {
                i += 4
                return null
            }
            throw IllegalArgumentException("Invalid null at index $i")
        }
    }
}
