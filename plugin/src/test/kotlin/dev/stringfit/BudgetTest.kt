package dev.stringfit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetTest {

    private fun site(
        name: String = "s",
        preview: String = "P",
        maxWidth: Int = 400,
        intrinsic: Int = 200,
        maxLines: Int = 1,
        linesNeeded: Int = 1,
    ) = Site(name, preview, maxWidth, intrinsic, maxLines, linesNeeded)

    // ---- classification --------------------------------------------------

    @Test
    fun `single line with bounded width is HARD`() {
        assertEquals(SiteClass.HARD, Budget.classify(site(maxLines = 1)))
    }

    @Test
    fun `unbounded width is FREE regardless of maxLines`() {
        assertEquals(SiteClass.FREE, Budget.classify(site(maxWidth = Int.MAX_VALUE)))
        assertEquals(
            SiteClass.FREE,
            Budget.classify(site(maxWidth = Budget.UNBOUNDED_WIDTH_PX, maxLines = 1)),
        )
    }

    @Test
    fun `default maxLines is SOFT not HARD`() {
        assertEquals(SiteClass.SOFT, Budget.classify(site(maxLines = Int.MAX_VALUE)))
        assertEquals(SiteClass.SOFT, Budget.classify(site(maxLines = 3)))
    }

    // ---- truncation ------------------------------------------------------

    @Test
    fun `single line text wider than its box is cut off`() {
        // the real Seal finding: 297px of text in a 272px box
        assertTrue(Budget.isCutOff(site(maxWidth = 272, intrinsic = 297, maxLines = 1)))
    }

    @Test
    fun `wrapping text wider than its box is NOT cut off`() {
        // Seal's download_hint: wants 1018px on one line, gets 440px, wraps to 3
        // lines and is perfectly fine. The naive rule called this broken.
        assertFalse(
            Budget.isCutOff(
                site(maxWidth = 440, intrinsic = 1018, maxLines = Int.MAX_VALUE, linesNeeded = 3),
            ),
        )
    }

    @Test
    fun `wrapping text needing more lines than allowed is cut off`() {
        assertTrue(
            Budget.isCutOff(site(maxWidth = 440, intrinsic = 1018, maxLines = 2, linesNeeded = 3)),
        )
        assertFalse(
            Budget.isCutOff(site(maxWidth = 440, intrinsic = 1018, maxLines = 3, linesNeeded = 3)),
        )
    }

    @Test
    fun `unbounded sites are never cut off`() {
        assertFalse(Budget.isCutOff(site(maxWidth = Int.MAX_VALUE, intrinsic = 9999)))
    }

    // ---- headroom --------------------------------------------------------

    @Test
    fun `width headroom is available over wanted, and only for HARD sites`() {
        assertEquals(2.0, Budget.widthHeadroom(site(maxWidth = 400, intrinsic = 200))!!, 1e-9)
        assertNull(Budget.widthHeadroom(site(maxLines = Int.MAX_VALUE)))
        assertNull(Budget.widthHeadroom(site(maxWidth = Int.MAX_VALUE)))
    }

    @Test
    fun `line headroom applies to SOFT sites only`() {
        assertEquals(
            1.5,
            Budget.lineHeadroom(site(maxLines = 3, linesNeeded = 2))!!,
            1e-9,
        )
        assertNull(Budget.lineHeadroom(site(maxLines = 1)))
    }

    @Test
    fun `zero intrinsic width does not divide by zero`() {
        assertNull(Budget.widthHeadroom(site(intrinsic = 0)))
    }

    // ---- conflict --------------------------------------------------------

    @Test
    fun `conflict fires on a real world spread`() {
        // Seal's video_title_sample_text: 0.96x .. 2.03x across 5 sites
        val v = Budget.verdict(
            StringEntry("title", "Title"),
            listOf(
                site(preview = "card", maxWidth = 192, intrinsic = 200),
                site(preview = "detail", maxWidth = 406, intrinsic = 200),
            ),
        )
        assertTrue(v.conflict, "2.1x spread should be flagged")
    }

    @Test
    fun `conflict does not fire on a mild spread`() {
        val v = Budget.verdict(
            StringEntry("msg", "Message"),
            listOf(
                site(preview = "a", maxWidth = 282, intrinsic = 200),
                site(preview = "b", maxWidth = 336, intrinsic = 200),
            ),
        )
        assertFalse(v.conflict, "1.2x spread is not a conflict")
    }

    @Test
    fun `a string used many times in roomy places is not a conflict`() {
        val v = Budget.verdict(
            StringEntry("ok", "OK"),
            List(6) { site(preview = "p$it", maxWidth = 600, intrinsic = 100) },
        )
        assertFalse(v.conflict)
    }

    @Test
    fun `conflict needs at least two distinct sites`() {
        val v = Budget.verdict(
            StringEntry("ok", "OK"),
            listOf(
                site(preview = "same", maxWidth = 100, intrinsic = 100),
                site(preview = "same", maxWidth = 100, intrinsic = 100),
            ),
        )
        assertFalse(v.conflict)
    }

    // ---- sample copy -----------------------------------------------------

    @Test
    fun `placeholder copy is recognised so it is not reported as a bug`() {
        assertTrue(Budget.looksLikeSample(StringEntry("video_creator_sample_text", "Creator")))
        assertTrue(Budget.looksLikeSample(StringEntry("body", "Lorem ipsum dolor sit amet")))
        assertFalse(Budget.looksLikeSample(StringEntry("action_cancel", "Cancel")))
        assertFalse(Budget.looksLikeSample(StringEntry("resample_audio", "Resample")))
    }

    // ---- whole-report ----------------------------------------------------

    @Test
    fun `analyze splits measured, unmeasured and unused`() {
        val catalog = listOf(
            StringEntry("rendered", "Hello"),
            StringEntry("referenced_only", "Hi"),
            StringEntry("dead", "Nobody"),
            StringEntry("not_translatable", "DEBUG", translatable = false),
        )
        val report = Budget.analyze(
            catalog = catalog,
            sites = listOf(site(name = "rendered")),
            referenced = setOf("rendered", "referenced_only"),
        )
        assertEquals(listOf("rendered"), report.measured.map { it.name })
        assertEquals(listOf("referenced_only"), report.notMeasured)
        assertEquals(listOf("dead"), report.unused)
        assertEquals(3, report.catalogSize)
    }

    @Test
    fun `cutOff excludes sample copy but conflicts still surface`() {
        val report = Budget.analyze(
            catalog = listOf(StringEntry("video_sample_text", "A long creator name")),
            sites = listOf(site(name = "video_sample_text", maxWidth = 272, intrinsic = 297)),
            referenced = setOf("video_sample_text"),
        )
        assertTrue(report.measured.single().isCutOff)
        assertTrue(report.cutOff.isEmpty(), "sample copy must not be reported as a bug")
    }

    @Test
    fun `translatable set honours unused triage`() {
        val catalog = listOf(
            StringEntry("live", "Live"),
            StringEntry("dead_ignore", "x"),
            StringEntry("dead_translate", "y"),
            StringEntry("dead_keep", "z"),
        )
        val report = Budget.analyze(
            catalog = catalog,
            sites = emptyList(),
            referenced = setOf("live"),
            triaged = mapOf(
                "dead_ignore" to UnusedStatus.IGNORE,
                "dead_translate" to UnusedStatus.TRANSLATE,
                "dead_keep" to UnusedStatus.KEEP,
            ),
        )
        val names = report.translatable(catalog).map { it.name }
        assertTrue("live" in names)
        assertTrue("dead_translate" in names, "explicitly queued for translation")
        assertFalse("dead_ignore" in names, "ignored strings are not pushed")
        assertFalse("dead_keep" in names, "undecided strings are reported, not pushed")
    }
}
