package dev.stringfit

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalesTest {

    @Test
    fun `rtl detection covers legacy android qualifiers`() {
        assertTrue(Locales.isRtl("ar"))
        assertTrue(Locales.isRtl("ar-rEG"))
        assertTrue(Locales.isRtl("iw"), "Android still uses iw for Hebrew")
        assertTrue(Locales.isRtl("he"))
        assertTrue(Locales.isRtl("fa"))
        assertFalse(Locales.isRtl("de"))
        assertFalse(Locales.isRtl("in"), "Indonesian is LTR despite the legacy code")
    }

    @Test
    fun `language strips region and bcp47 prefix`() {
        assertEquals("pt", Locales.language("pt-rBR"))
        assertEquals("sr", Locales.language("b+sr+Latn"))
        assertEquals("de", Locales.language("de"))
    }

    @Test
    fun `non-locale resource qualifiers are not mistaken for languages`() {
        listOf("night", "v31", "w600dp", "sw720dp", "land", "xxhdpi", "ldrtl")
            .forEach { assertFalse(Locales.looksLikeLocale(it), "$it is not a locale") }
        listOf("de", "pt-rBR", "zh-rCN", "b+sr+Latn")
            .forEach { assertTrue(Locales.looksLikeLocale(it), "$it is a locale") }
    }

    @Test
    fun `discover finds shipped locales and skips other qualifiers`() {
        val d = createTempDirectory("loc").toFile()
        listOf("values", "values-de", "values-ar", "values-night", "values-w600dp", "values-pt-rBR")
            .forEach { File(d, "res/$it").mkdirs() }
        assertEquals(listOf("ar", "de", "pt-rBR"), Locales.discover(listOf(File(d, "res"))))
    }

    @Test
    fun `presets expand and mix with explicit codes`() {
        val resolved = Locales.resolve(listOf("high-risk", "pt-rBR"), emptyList())
        assertTrue("de" in resolved && "ar" in resolved)
        assertTrue("pt-rBR" in resolved)
        assertEquals(resolved.distinct(), resolved, "no duplicates")
    }

    @Test
    fun `empty config defaults to what the project ships`() {
        val d = createTempDirectory("loc").toFile()
        listOf("values", "values-de").forEach { File(d, "res/$it").mkdirs() }
        assertEquals(
            listOf("de", Locales.RTL_PROBE),
            Locales.resolve(emptyList(), listOf(File(d, "res"))),
            "the direction probe is always measured",
        )
    }

    @Test
    fun `rtl asymmetry uses the direction probe, never a real language`() {
        fun s(loc: String, w: Int) = Site("t", "P", w, 100, 1, 1, locale = loc)
        val probe = Locales.RTL_PROBE

        assertTrue(Budget.rtlAsymmetry(listOf(s("", 400), s(probe, 300))).isNotEmpty())
        assertTrue(Budget.rtlAsymmetry(listOf(s("", 400), s(probe, 400))).isEmpty())
        assertTrue(
            Budget.rtlAsymmetry(listOf(s("", 400), s(probe, 397))).isEmpty(),
            "2% tolerance absorbs rounding",
        )
        // Regression: Arabic made a sibling button narrower, which freed width
        // and looked like a mirroring bug. Different text is not evidence of
        // asymmetry -- only the probe, which holds the text constant, is.
        assertTrue(
            Budget.rtlAsymmetry(listOf(s("", 439), s("ar", 483))).isEmpty(),
            "a real RTL language must never trigger this check",
        )
        assertTrue(Budget.rtlAsymmetry(listOf(s("", 400), s("de", 300))).isEmpty())
    }

    @Test
    fun `a layout that keeps its width but never moves is caught`() {
        // absolutePadding(left = 120.dp) on a 360dp root: same width in both
        // directions, but the inset stays on the left. Only position reveals it.
        val ltr = Site(
            "t", "P", 480, 200, 1, 1, widthDp = 360,
            locale = "", leftPx = 240, rightPx = 456,
        )
        val rtlBroken = ltr.copy(locale = Locales.RTL_PROBE, leftPx = 240, rightPx = 456)
        val rtlCorrect = ltr.copy(locale = Locales.RTL_PROBE, leftPx = 264, rightPx = 480)

        val broken = Budget.rtlAsymmetry(listOf(ltr, rtlBroken))
        assertEquals(1, broken.size)
        assertEquals(RtlAsymmetry.Kind.POSITION, broken.single().kind)

        assertTrue(
            Budget.rtlAsymmetry(listOf(ltr, rtlCorrect)).isEmpty(),
            "a correctly mirrored layout must stay silent",
        )
    }

    @Test
    fun `missing position data does not produce a false mirroring claim`() {
        val ltr = Site("t", "P", 480, 200, 1, 1, widthDp = 360, locale = "")
        val rtl = ltr.copy(locale = Locales.RTL_PROBE)
        assertTrue(Budget.rtlAsymmetry(listOf(ltr, rtl)).isEmpty())
    }

    @Test
    fun `the direction probe is not reported as a language`() {
        val sites = listOf(
            Site("a", "P", 400, 100, 1, 1, locale = ""),
            Site("a", "P", 400, 100, 1, 1, locale = Locales.RTL_PROBE),
            Site("a", "P", 400, 121, 1, 1, locale = "de"),
        )
        assertEquals(listOf("de"), Budget.summarise(sites).map { it.locale })
    }

    @Test
    fun `locale summary reports expansion relative to source`() {
        val sites = listOf(
            Site("a", "P", 400, 100, 1, 1, locale = ""),
            Site("a", "P", 400, 135, 1, 1, locale = "de"),
        )
        val de = Budget.summarise(sites).single()
        assertEquals("de", de.locale)
        assertEquals(1.35, de.expansion!!, 1e-9)
    }
}
