package app.sawaari.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlin.random.Random

/** The shared helpers must give exactly what the Android-only Java versions gave before. */
class TextJvmTest {
    private val rnd = Random(42)

    @Test fun fixedMatchesStringFormat() {
        repeat(20_000) {
            val v = when (it % 3) {
                0 -> (rnd.nextDouble() - 0.5) * 200
                1 -> rnd.nextInt(-100_000, 100_000) / 1000.0 // short decimals like 12.345, where rounding ties happen
                else -> rnd.nextDouble() * 1e-4
            }
            for (d in listOf(0, 1, 2, 5)) assertEquals("$v", String.format(Locale.US, "%.${d}f", v), Text.fixed(v, d))
        }
    }

    @Test fun groupedMatchesStringFormat() {
        repeat(5_000) {
            val v = rnd.nextLong(0, 10_000_000)
            assertEquals(String.format(Locale.ENGLISH, "%,d", v), Text.grouped(v))
        }
    }

    @Test fun urlMatchesJava() {
        val alphabet = "aZ09 .-*_~!@#$%^&()+=/?:,;'\"éಬ"
        repeat(5_000) {
            val s = buildString { repeat(rnd.nextInt(0, 12)) { append(if (rnd.nextInt(10) == 0) "😀" else alphabet.random(rnd).toString()) } }
            val enc = URLEncoder.encode(s, "UTF-8")
            assertEquals(s, enc, Text.urlEncode(s))
            assertEquals(enc, URLDecoder.decode(enc, "UTF-8"), Text.urlDecode(enc))
        }
    }
}
