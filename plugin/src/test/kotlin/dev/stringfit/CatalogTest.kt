package dev.stringfit

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CatalogTest {

    private fun tmp(): File = createTempDirectory("stringfit").toFile()

    private fun strings(dir: File, body: String): File {
        val f = File(dir, "res/values/strings.xml")
        f.parentFile.mkdirs()
        f.writeText("""<?xml version="1.0" encoding="utf-8"?><resources>$body</resources>""")
        return f
    }

    @Test
    fun `parses strings, plurals and arrays and honours translatable false`() {
        val d = tmp()
        val f = strings(
            d,
            """
            <string name="hello">Hello</string>
            <string name="debug_tag" translatable="false">DEBUG</string>
            <plurals name="items"><item quantity="one">%d item</item></plurals>
            <string-array name="modes"><item>A</item></string-array>
            """,
        )
        val entries = Catalog.parseStringsXml(f).associateBy { it.name }
        assertEquals(4, entries.size)
        assertEquals("Hello", entries.getValue("hello").value)
        assertFalse(entries.getValue("debug_tag").translatable)
        assertEquals("plurals", entries.getValue("items").kind)
        assertEquals("string-array", entries.getValue("modes").kind)
    }

    @Test
    fun `catalog reads values but not translated locales`() {
        val d = tmp()
        strings(d, """<string name="hello">Hello</string>""")
        File(d, "res/values-de").mkdirs()
        File(d, "res/values-de/strings.xml")
            .writeText("""<resources><string name="only_de">Hallo</string></resources>""")
        val names = Catalog.parseCatalog(listOf(File(d, "res"))).map { it.name }
        assertEquals(listOf("hello"), names)
    }

    @Test
    fun `malformed xml does not blow up the build`() {
        val d = tmp()
        val f = File(d, "res/values/strings.xml")
        f.parentFile.mkdirs()
        f.writeText("<resources><string name=oops")
        assertEquals(emptyList(), Catalog.parseStringsXml(f))
    }

    @Test
    fun `reference scan finds plain, aliased and xml references`() {
        val d = tmp()
        File(d, "src").mkdirs()
        File(d, "src/Ui.kt").writeText(
            """
            fun a() = stringResource(R.string.plain)
            fun b() = stringResource(searchR.string.aliased)
            fun c() = getQuantityString(CoreUiR.plurals.aliased_plural, 1)
            // R.string.in_line_comment
            /* R.string.in_block_comment */
            """.trimIndent()
        )
        File(d, "src/layout.xml").writeText("""<TextView android:text="@string/from_xml"/>""")
        val refs = Catalog.scanReferences(listOf(d))
        assertTrue("plain" in refs)
        assertTrue("aliased" in refs, "R-class import aliases must be found")
        assertTrue("aliased_plural" in refs)
        assertTrue("from_xml" in refs)
        assertFalse("in_line_comment" in refs, "commented-out code is not a reference")
        assertFalse("in_block_comment" in refs)
    }

    @Test
    fun `reference scan skips build directories`() {
        val d = tmp()
        File(d, "build/generated").mkdirs()
        File(d, "build/generated/Gen.kt").writeText("val x = R.string.generated_only")
        assertFalse("generated_only" in Catalog.scanReferences(listOf(d)))
    }

    @Test
    fun `triage round trips`() {
        val d = tmp()
        val f = File(d, "stringfit.yml")
        f.writeText(
            """
            unused:
              legacy_hint: ignore        # gone in Q4
              paywall_title: translate
              share_email: keep
              bogus_line: nonsense
            other:
              ignored_section: ignore
            """.trimIndent()
        )
        val t = Catalog.parseTriage(f)
        assertEquals(UnusedStatus.IGNORE, t["legacy_hint"])
        assertEquals(UnusedStatus.TRANSLATE, t["paywall_title"])
        assertEquals(UnusedStatus.KEEP, t["share_email"])
        assertFalse("bogus_line" in t, "unknown status is skipped, not guessed")
        assertFalse("ignored_section" in t, "only the unused: section is read")
    }

    @Test
    fun `baseline preserves existing decisions and defaults new ones to keep`() {
        val d = tmp()
        val f = File(d, "stringfit.yml")
        Catalog.writeTriageBaseline(f, listOf("a", "b"), mapOf("a" to UnusedStatus.IGNORE))
        val t = Catalog.parseTriage(f)
        assertEquals(UnusedStatus.IGNORE, t["a"], "existing decision must survive")
        assertEquals(UnusedStatus.KEEP, t["b"])
    }

    @Test
    fun `site rows parse and bad rows are skipped`() {
        val d = tmp()
        File(d, "sites").mkdirs()
        File(d, "sites/p.tsv").writeText(
            listOf(
                "cancel\tDialogPreview\t624\t89\t1\t1\t89\t360\t1.0",
                "broken\trow",
                "hint\tHintPreview\t440\t1018\t2147483647\t3\t440\t360\t1.3",
            ).joinToString("\n")
        )
        val sites = Catalog.parseSites(File(d, "sites"))
        assertEquals(2, sites.size)
        assertEquals("cancel", sites[0].stringName)
        assertEquals(624, sites[0].maxWidthPx)
        assertEquals(1.3f, sites[1].fontScale)
        assertEquals(SiteClass.SOFT, Budget.classify(sites[1]))
    }
}
