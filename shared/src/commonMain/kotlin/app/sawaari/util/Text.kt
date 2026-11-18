package app.sawaari.util

import kotlin.math.abs

/**
 * Small text helpers that work on both Android and iPhone (java.lang.String.format,
 * URLEncoder and URLDecoder are only on Android).
 */
object Text {
    /**
     * [value] with exactly [decimals] digits after the point, the same as String.format(Locale.US, "%.Nf"):
     * Java rounds the shortest decimal form of the number half up, so this does too (1.45 -> "1.5").
     */
    fun fixed(value: Double, decimals: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
        // Shortest decimal form, e.g. "1.45", "1.0E-5", "1.2345E7".
        val str = abs(value).toString()
        val mantissa = str.substringBefore('E').substringBefore('e')
        val exp = if (str.contains('E') || str.contains('e')) str.substringAfterLast('E').substringAfterLast('e').toInt() else 0
        val intPart = mantissa.substringBefore('.')
        val fracPart = if ('.' in mantissa) mantissa.substringAfter('.') else ""
        val digits = (intPart + fracPart).trimStart('0').ifEmpty { "0" }
        // Position of the decimal point within [digits], counted from the left.
        val point = intPart.trimStart('0').length.let { lead ->
            if (lead > 0) lead + exp else -(fracPart.length - fracPart.trimStart('0').length) + exp
        }
        val plainInt: String
        val plainFrac: String
        when {
            digits == "0" -> { plainInt = "0"; plainFrac = "" }
            point <= 0 -> { plainInt = "0"; plainFrac = "0".repeat(-point) + digits }
            point >= digits.length -> { plainInt = digits + "0".repeat(point - digits.length); plainFrac = "" }
            else -> { plainInt = digits.substring(0, point); plainFrac = digits.substring(point) }
        }
        val keep = (plainInt + plainFrac.padEnd(decimals, '0').take(decimals)).toCharArray()
        val roundUp = plainFrac.length > decimals && plainFrac[decimals] >= '5'
        var out = keep.concatToString()
        if (roundUp) {
            var i = keep.size - 1
            while (i >= 0 && keep[i] == '9') { keep[i] = '0'; i-- }
            out = if (i >= 0) { keep[i] = keep[i] + 1; keep.concatToString() } else "1" + keep.concatToString()
        }
        val intLen = out.length - decimals
        val body = if (decimals == 0) out else out.substring(0, intLen) + "." + out.substring(intLen)
        return if (negative) "-$body" else body
    }

    /** [value] with commas between thousands, like "%,d" in Locale.ENGLISH (12,345). */
    fun grouped(value: Long): String {
        val digits = abs(value).toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return if (value < 0) "-$out" else out.toString()
    }

    /** Same output as java.net.URLEncoder.encode(s, "UTF-8"). */
    fun urlEncode(s: String): String {
        val out = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '*' || c == '_' -> out.append(c)
                c == ' ' -> out.append('+')
                else -> out.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    /** Same as java.net.URLDecoder.decode(s, "UTF-8"); throws IllegalArgumentException on a broken % escape. */
    fun urlDecode(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '+' -> { out.append(' '); i++ }
                '%' -> {
                    val bytes = ArrayList<Byte>()
                    while (i < s.length && s[i] == '%') {
                        require(i + 2 < s.length) { "Incomplete trailing escape (%) pattern" }
                        val hi = s[i + 1].digitToIntOrNull(16)
                        val lo = s[i + 2].digitToIntOrNull(16)
                        require(hi != null && lo != null) { "Illegal hex characters in escape (%) pattern" }
                        bytes.add(((hi shl 4) or lo).toByte())
                        i += 3
                    }
                    out.append(bytes.toByteArray().decodeToString())
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
