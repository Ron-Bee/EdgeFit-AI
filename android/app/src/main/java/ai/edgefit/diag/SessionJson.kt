package ai.edgefit.diag

/**
 * Minimal strict JSON codec for session records. Pure JVM with zero
 * dependencies (org.json is an Android stub that throws in unit tests).
 * Supports null, Boolean, Long/Int, Double (finite only), String,
 * List, and Map with String keys. Anything else is refused loudly.
 */
object SessionJson {
    const val MAX_DEPTH = 32

    class JsonError(message: String) : Exception(message)

    fun stringify(value: Any?): String {
        val out = StringBuilder()
        writeValue(out, value, 0)
        return out.toString()
    }

    private fun writeValue(out: StringBuilder, value: Any?, depth: Int) {
        if (depth > MAX_DEPTH) throw JsonError("depth exceeded")
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Int -> out.append(value.toString())
            is Long -> out.append(value.toString())
            is Double -> {
                if (!value.isFinite()) throw JsonError("non-finite double")
                out.append(value.toString())
            }
            is Float -> {
                if (!value.isFinite()) throw JsonError("non-finite float")
                out.append(value.toString())
            }
            is String -> writeString(out, value)
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeValue(out, item, depth + 1)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (key !is String) throw JsonError("non-string key")
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    writeValue(out, item, depth + 1)
                }
                out.append('}')
            }
            else -> throw JsonError("unsupported type ${value::class}")
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    if (char.code < 0x20) {
                        out.append("\\u%04x".format(char.code))
                    } else {
                        out.append(char)
                    }
                }
            }
        }
        out.append('"')
    }

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue(0)
        parser.skipGaps()
        if (!parser.atEnd()) throw JsonError("trailing characters")
        return value
    }

    private class Parser(val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipGaps() {
            while (!atEnd() && text[pos] in " \t\r\n") pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonError("depth exceeded")
            skipGaps()
            if (atEnd()) throw JsonError("unexpected end")
            return when (val char = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw JsonError("unexpected '$char' at $pos")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) {
                throw JsonError("bad literal at $pos")
            }
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipGaps()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipGaps()
                if (atEnd() || text[pos] != '"') {
                    throw JsonError("expected string key at $pos")
                }
                val key = readString()
                skipGaps()
                if (atEnd() || text[pos] != ':') {
                    throw JsonError("expected ':' at $pos")
                }
                pos++
                map[key] = readValue(depth + 1)
                skipGaps()
                if (atEnd()) throw JsonError("unterminated object")
                if (text[pos] == '}') {
                    pos++
                    return map
                }
                if (text[pos] != ',') throw JsonError("expected ',' at $pos")
                pos++
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipGaps()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(readValue(depth + 1))
                skipGaps()
                if (atEnd()) throw JsonError("unterminated array")
                if (text[pos] == ']') {
                    pos++
                    return list
                }
                if (text[pos] != ',') throw JsonError("expected ',' at $pos")
                pos++
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonError("unterminated string")
                val char = text[pos++]
                when (char) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonError("bad escape")
                        when (val esc = text[pos++]) {
                            '"', '\\', '/' -> out.append(esc)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) {
                                    throw JsonError("bad unicode escape")
                                }
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonError("bad unicode escape")
                                pos += 4
                                out.append(code.toChar())
                            }
                            else -> throw JsonError("bad escape '\\$esc'")
                        }
                    }
                    else -> {
                        if (char.code < 0x20) {
                            throw JsonError("raw control character")
                        }
                        out.append(char)
                    }
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            if (atEnd() || text[pos] !in '0'..'9') {
                throw JsonError("bad number at $start")
            }
            if (text[pos] == '0') {
                pos++
                if (!atEnd() && text[pos] in '0'..'9') {
                    throw JsonError("leading zero at $start")
                }
            } else {
                while (!atEnd() && text[pos] in '0'..'9') pos++
            }
            var isDouble = false
            if (!atEnd() && text[pos] == '.') {
                isDouble = true
                pos++
                if (atEnd() || text[pos] !in '0'..'9') {
                    throw JsonError("bad fraction at $start")
                }
                while (!atEnd() && text[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                if (atEnd() || text[pos] !in '0'..'9') {
                    throw JsonError("bad exponent at $start")
                }
                while (!atEnd() && text[pos] in '0'..'9') pos++
            }
            val token = text.substring(start, pos)
            return try {
                if (isDouble) token.toDouble() else token.toLong()
            } catch (_: NumberFormatException) {
                throw JsonError("number out of range at $start")
            }
        }
    }
}
