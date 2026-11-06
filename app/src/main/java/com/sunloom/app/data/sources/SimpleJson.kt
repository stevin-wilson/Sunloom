package com.sunloom.app.data.sources

import org.json.JSONArray
import org.json.JSONObject

/**
 * Article XV (Constitution v0.4): Strict JSON Parser.
 * Uses Android's standard `org.json` parser when active (Android runtime & Robolectric)
 * combined with strict RFC 8259 structural and depth validation (max depth 32, no trailing
 * tokens, no unclosed strings/containers) so malformed input is always rejected deterministically.
 */
object SimpleJson {

    const val MAX_NESTING_DEPTH = 32

    class JsonObj(val map: Map<String, Any?>) {
        fun obj(key: String): JsonObj? = map[key] as? JsonObj
        fun arr(key: String): JsonArr? = map[key] as? JsonArr
        fun str(key: String, default: String = ""): String {
            val v = map[key] ?: return default
            if (v === JSONObject.NULL) return default
            return v.toString()
        }
        fun int(key: String, default: Int = 0): Int {
            return when (val v = map[key]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: default
                else -> default
            }
        }
        fun bool(key: String, default: Boolean = false): Boolean {
            return when (val v = map[key]) {
                is Boolean -> v
                is String -> v.toBooleanStrictOrNull() ?: default
                else -> default
            }
        }
        fun keys(): Set<String> = map.keys
    }

    class JsonArr(val list: List<Any?>) {
        val size: Int get() = list.size
        fun obj(index: Int): JsonObj? = list.getOrNull(index) as? JsonObj
        fun str(index: Int, default: String = ""): String {
            val v = list.getOrNull(index) ?: return default
            if (v === JSONObject.NULL) return default
            return v.toString()
        }
        fun int(index: Int, default: Int = 0): Int {
            return when (val v = list.getOrNull(index)) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: default
                else -> default
            }
        }
    }

    private val isOrgJsonActive: Boolean by lazy {
        runCatching { JSONObject("{\"_\":1}").length() == 1 }.getOrDefault(false)
    }

    fun parseObject(text: String): JsonObj? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return runCatching {
            // Strict RFC 8259 syntax & nesting-depth pre-validation (rejects depth > 32, trailing commas, trailing tokens)
            val fallbackObj = StrictParser(trimmed).parseRoot() as? JsonObj ?: return null
            if (isOrgJsonActive) {
                wrapOrgJsonObject(JSONObject(trimmed), depth = 0)
            } else {
                fallbackObj
            }
        }.getOrNull()
    }

    fun parseArray(text: String): JsonArr? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        return runCatching {
            val fallbackArr = StrictParser(trimmed).parseRoot() as? JsonArr ?: return null
            if (isOrgJsonActive) {
                wrapOrgJsonArray(JSONArray(trimmed), depth = 0)
            } else {
                fallbackArr
            }
        }.getOrNull()
    }

    private fun wrapOrgJsonObject(obj: JSONObject, depth: Int): JsonObj {
        if (depth > MAX_NESTING_DEPTH) {
            throw IllegalArgumentException("JSON nesting exceeds maximum depth ($MAX_NESTING_DEPTH)")
        }
        val map = LinkedHashMap<String, Any?>()
        val iter = obj.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            map[k] = wrapOrgJsonValue(obj.opt(k), depth + 1)
        }
        return JsonObj(map)
    }

    private fun wrapOrgJsonArray(arr: JSONArray, depth: Int): JsonArr {
        if (depth > MAX_NESTING_DEPTH) {
            throw IllegalArgumentException("JSON nesting exceeds maximum depth ($MAX_NESTING_DEPTH)")
        }
        val list = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(wrapOrgJsonValue(arr.opt(i), depth + 1))
        }
        return JsonArr(list)
    }

    private fun wrapOrgJsonValue(value: Any?, depth: Int): Any? {
        return when {
            value == null || value === JSONObject.NULL -> null
            value is JSONObject -> wrapOrgJsonObject(value, depth)
            value is JSONArray -> wrapOrgJsonArray(value, depth)
            else -> value
        }
    }

    private class StrictParser(private val src: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            val value = parseValue(depth = 0)
            skipWhitespace()
            if (pos != src.length) {
                throw IllegalArgumentException("Unexpected trailing characters at position $pos")
            }
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > MAX_NESTING_DEPTH) {
                throw IllegalArgumentException("JSON nesting exceeds maximum depth ($MAX_NESTING_DEPTH)")
            }
            skipWhitespace()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON input")
            return when (val ch = src[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected character '$ch' at $pos")
            }
        }

        private fun parseObject(depth: Int): JsonObj {
            val map = LinkedHashMap<String, Any?>()
            expectChar('{')
            skipWhitespace()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return JsonObj(map)
            }
            while (true) {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '"') {
                    throw IllegalArgumentException("Expected quoted object key at $pos")
                }
                val key = parseString()
                skipWhitespace()
                expectChar(':')
                val value = parseValue(depth)
                map[key] = value
                skipWhitespace()
                if (pos >= src.length) throw IllegalArgumentException("Unclosed JSON object")
                when (src[pos]) {
                    ',' -> {
                        pos++
                        skipWhitespace()
                        if (pos < src.length && src[pos] == '}') {
                            throw IllegalArgumentException("Trailing comma in JSON object at $pos")
                        }
                    }
                    '}' -> {
                        pos++
                        break
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' in object at $pos")
                }
            }
            return JsonObj(map)
        }

        private fun parseArray(depth: Int): JsonArr {
            val list = ArrayList<Any?>()
            expectChar('[')
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return JsonArr(list)
            }
            while (true) {
                list.add(parseValue(depth))
                skipWhitespace()
                if (pos >= src.length) throw IllegalArgumentException("Unclosed JSON array")
                when (src[pos]) {
                    ',' -> {
                        pos++
                        skipWhitespace()
                        if (pos < src.length && src[pos] == ']') {
                            throw IllegalArgumentException("Trailing comma in JSON array at $pos")
                        }
                    }
                    ']' -> {
                        pos++
                        break
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' in array at $pos")
                }
            }
            return JsonArr(list)
        }

        private fun parseString(): String {
            expectChar('"')
            val sb = StringBuilder()
            var closed = false
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') {
                    closed = true
                    break
                }
                if (c.code < 0x20) {
                    throw IllegalArgumentException("Unescaped control character in JSON string")
                }
                if (c == '\\') {
                    if (pos >= src.length) throw IllegalArgumentException("Unterminated escape sequence")
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) {
                                throw IllegalArgumentException("Invalid unicode escape at $pos")
                            }
                            val hex = src.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16)
                                ?: throw IllegalArgumentException("Invalid hex in unicode escape '$hex'")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape character '\\$esc'")
                    }
                } else {
                    sb.append(c)
                }
            }
            if (!closed) throw IllegalArgumentException("Unterminated JSON string")
            return sb.toString()
        }

        private fun parseBoolean(): Boolean {
            return when {
                src.startsWith("true", pos) -> {
                    pos += 4
                    true
                }
                src.startsWith("false", pos) -> {
                    pos += 5
                    false
                }
                else -> throw IllegalArgumentException("Invalid boolean literal at $pos")
            }
        }

        private fun parseNull(): Any? {
            if (!src.startsWith("null", pos)) {
                throw IllegalArgumentException("Invalid null literal at $pos")
            }
            pos += 4
            return null
        }

        private fun parseNumber(): Number {
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++
            if (pos >= src.length || src[pos] !in '0'..'9') {
                throw IllegalArgumentException("Invalid number literal at $start")
            }
            while (pos < src.length && src[pos] in "0123456789+-.eE") {
                pos++
            }
            val token = src.substring(start, pos)
            return if (token.contains('.') || token.contains('e') || token.contains('E')) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid float '$token'")
            } else {
                token.toLongOrNull() ?: throw IllegalArgumentException("Invalid integer '$token'")
            }
        }

        private fun expectChar(expected: Char) {
            if (pos >= src.length || src[pos] != expected) {
                throw IllegalArgumentException("Expected '$expected' at position $pos")
            }
            pos++
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }
    }
}
