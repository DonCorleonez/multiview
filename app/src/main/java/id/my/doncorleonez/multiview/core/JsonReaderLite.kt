package id.my.doncorleonez.multiview.core

import java.io.Reader

/**
 * Minimal streaming JSON reader (Gson/android.util.JsonReader style), pure Kotlin so it runs in JVM
 * unit tests. Used to walk Xtream responses without building a full tree: `get_live_streams` can be
 * tens of MB with 30 000+ entries, and we only need four fields per entry.
 *
 * Scalars are returned as strings (numbers and booleans included); null is returned as null.
 */
class JsonReaderLite(private val reader: Reader) {

    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    private val buf = CharArray(16 * 1024)
    private var pos = 0
    private var limit = 0

    // Container stack: 'o' = object expecting name, 'v' = object expecting value, 'a' = array
    private val stack = CharArray(256)
    private var depth = 0
    private var needComma = BooleanArray(256)
    private var peeked: Token? = null

    private fun fill(): Boolean {
        if (pos < limit) return true
        limit = reader.read(buf, 0, buf.size)
        pos = 0
        return limit > 0
    }

    private fun nextNonWs(): Char {
        while (true) {
            if (!fill()) return '\u0000'
            val c = buf[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { pos++; continue }
            return c
        }
    }

    private fun syntax(msg: String): Nothing = throw IllegalStateException("JSON: $msg")

    fun peek(): Token {
        peeked?.let { return it }
        val top = if (depth == 0) ' ' else stack[depth - 1]
        var c = nextNonWs()
        if (top == 'o' || top == 'a') {
            if (c == '}' && top == 'o') return Token.END_OBJECT.also { peeked = it }
            if (c == ']' && top == 'a') return Token.END_ARRAY.also { peeked = it }
            if (needComma[depth - 1]) {
                if (c != ',') syntax("expected ',' got '$c'")
                pos++
                c = nextNonWs()
            }
            if (top == 'o') {
                if (c != '"') syntax("expected name")
                return Token.NAME.also { peeked = it }
            }
        } else if (top == 'v') {
            if (c != ':') syntax("expected ':'")
            pos++
            c = nextNonWs()
        }
        val t = when (c) {
            '{' -> Token.BEGIN_OBJECT
            '[' -> Token.BEGIN_ARRAY
            '"' -> Token.STRING
            't', 'f' -> Token.BOOLEAN
            'n' -> Token.NULL
            '\u0000' -> Token.END
            else -> if (c == '-' || c in '0'..'9') Token.NUMBER else syntax("unexpected '$c'")
        }
        peeked = t
        return t
    }

    /** Called after a value has been fully consumed. */
    private fun afterValue() {
        if (depth == 0) return
        when (stack[depth - 1]) {
            'v' -> { stack[depth - 1] = 'o'; needComma[depth - 1] = true }
            'a' -> needComma[depth - 1] = true
        }
    }

    private fun push(kind: Char) {
        if (depth == stack.size) syntax("too deep")
        stack[depth] = kind
        needComma[depth] = false
        depth++
    }

    fun beginObject() { expect(Token.BEGIN_OBJECT); pos++; peeked = null; push('o') }
    fun endObject() { expect(Token.END_OBJECT); pos++; peeked = null; depth--; afterValue() }
    fun beginArray() { expect(Token.BEGIN_ARRAY); pos++; peeked = null; push('a') }
    fun endArray() { expect(Token.END_ARRAY); pos++; peeked = null; depth--; afterValue() }

    fun hasNext(): Boolean {
        val t = peek()
        return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END
    }

    fun nextName(): String {
        expect(Token.NAME)
        peeked = null
        pos++ // opening quote
        val s = readStringBody()
        stack[depth - 1] = 'v'
        return s
    }

    /** Returns any scalar as String; null for JSON null. Objects/arrays are an error. */
    fun nextStringOrNull(): String? {
        val t = peek()
        peeked = null
        val r: String? = when (t) {
            Token.STRING -> { pos++; readStringBody() }
            Token.NUMBER -> readLiteral()
            Token.BOOLEAN -> readLiteral()
            Token.NULL -> { readLiteral(); null }
            else -> syntax("expected scalar, got $t")
        }
        afterValue()
        return r
    }

    fun skipValue() {
        when (peek()) {
            Token.BEGIN_OBJECT -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            Token.BEGIN_ARRAY -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            Token.NAME -> { nextName(); skipValue() }
            else -> nextStringOrNull()
        }
    }

    private fun expect(t: Token) {
        val p = peek()
        if (p != t) syntax("expected $t, got $p")
    }

    private fun readLiteral(): String {
        val sb = StringBuilder()
        while (fill()) {
            val c = buf[pos]
            if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ':') break
            sb.append(c); pos++
        }
        return sb.toString()
    }

    private fun readStringBody(): String {
        val sb = StringBuilder()
        while (true) {
            if (!fill()) syntax("unterminated string")
            // fast path: copy run without escapes
            var i = pos
            while (i < limit && buf[i] != '"' && buf[i] != '\\') i++
            sb.append(buf, pos, i - pos)
            pos = i
            if (pos >= limit) continue
            val c = buf[pos++]
            if (c == '"') return sb.toString()
            // backslash
            if (!fill()) syntax("bad escape")
            when (val e = buf[pos++]) {
                '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                'r' -> sb.append('\r'); 't' -> sb.append('\t')
                'u' -> {
                    var code = 0
                    repeat(4) {
                        if (!fill()) syntax("bad unicode escape")
                        code = code * 16 + Character.digit(buf[pos++], 16).also { d -> if (d < 0) syntax("bad hex") }
                    }
                    sb.append(code.toChar())
                }
                else -> syntax("bad escape \\$e")
            }
        }
    }
}
