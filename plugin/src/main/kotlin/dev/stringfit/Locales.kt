package dev.stringfit

import java.io.File

/**
 * Which languages to measure, and what each one does to a layout.
 *
 * The default is not a fixed list: it is *the locales your project actually
 * ships*, discovered from `values-XX` directories. Testing `de` on an app with
 * no German translation measures nothing. The curated presets below are for
 * apps that want a specific subset, or that have no translations yet and want
 * to know what would happen.
 */
object Locales {

    /**
     * Scripts written right to left. Android resource qualifiers still use the
     * pre-ISO-639 codes for some of these (`iw` = Hebrew, `in` = Indonesian,
     * `ji` = Yiddish), so both spellings are listed.
     */
    private val RTL_LANGUAGES = setOf(
        "ar",  // Arabic
        "dv",  // Divehi
        "fa",  // Persian
        "he", "iw",  // Hebrew (iw is the legacy Android qualifier)
        "ku",  // Kurdish (Sorani)
        "ps",  // Pashto
        "sd",  // Sindhi
        "ug",  // Uyghur
        "ur",  // Urdu
        "yi", "ji",  // Yiddish
    )

    /**
     * Widely shipped app languages. Useful when you want a fixed matrix rather
     * than whatever happens to be in the project.
     */
    val POPULAR: List<String> = listOf(
        "ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko", "nl",
        "pl", "pt", "ru", "th", "tr", "vi", "zh",
    )

    /**
     * The languages most likely to break a layout, for a fast pre-flight check.
     * German and Russian for expansion, Arabic for RTL plus shaping, Japanese
     * and Thai for line breaking without spaces, Hindi for tall glyphs.
     */
    val HIGH_RISK: List<String> = listOf("de", "ru", "fr", "ar", "ja", "th", "hi")

    /** Android's built-in pseudo-locales: accented+expanded, and RTL. */
    val PSEUDO: List<String> = listOf("en-rXA", "ar-rXB")

    /**
     * Synthetic pass: source-locale text rendered with the layout direction
     * forced to RTL.
     *
     * Comparing a real RTL language against the source locale cannot tell you
     * whether a layout mirrors, because the text changed too -- a sibling that
     * shrinks frees up width and looks like an asymmetry. Holding the text
     * constant and flipping only the direction isolates the layout.
     */
    const val RTL_PROBE: String = "rtl-probe"

    fun isProbe(locale: String): Boolean = locale == RTL_PROBE

    fun isRtl(locale: String): Boolean =
        locale == RTL_PROBE || language(locale) in RTL_LANGUAGES

    /** `pt-rBR` -> `pt`, `b+sr+Latn` -> `sr`. */
    fun language(locale: String): String {
        val cleaned = locale.removePrefix("b+").replace('+', '-')
        return cleaned.substringBefore('-').lowercase()
    }

    /** `de` -> `de`, `pt-rBR` -> `pt-rBR`; the form Robolectric qualifiers want. */
    fun qualifier(locale: String): String = locale.replace("_", "-")

    /**
     * Locales the project actually has translations for, read from
     * `values-XX` directories. Non-locale qualifiers (`values-night`,
     * `values-w600dp`, `values-v31`) are excluded.
     */
    fun discover(resDirs: Collection<File>): List<String> =
        resDirs.filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { it.name.removePrefix("values-") }
            .filter(::looksLikeLocale)
            .distinct()
            .sorted()

    private val NON_LOCALE = setOf(
        "night", "notnight", "land", "port", "ldrtl", "ldltr", "round", "notround",
        "television", "car", "watch", "desk", "appliance", "vrheadset", "mask",
    )

    private val LOCALE_SHAPE = Regex("""^(b\+[A-Za-z+]+|[a-z]{2,3}(-r[A-Z0-9]{2,3})?)$""")

    fun looksLikeLocale(qualifier: String): Boolean {
        if (qualifier in NON_LOCALE) return false
        // density, size, version and dimension qualifiers
        if (qualifier.matches(Regex("""^(v\d+|sw\d+dp|w\d+dp|h\d+dp|\d+dpi|.*dpi)$"""))) return false
        return LOCALE_SHAPE.matches(qualifier)
    }

    /**
     * Resolve the locales to measure.
     *
     * [configured] wins if set; otherwise every locale the project ships. A
     * preset name (`popular`, `high-risk`, `pseudo`, `all`) expands in place.
     */
    fun resolve(configured: List<String>, resDirs: Collection<File>): List<String> {
        val shipped = discover(resDirs)
        // Always include the direction probe: it costs one render and is the
        // only way to attribute a width change to mirroring rather than text.
        if (configured.isEmpty()) return (shipped + RTL_PROBE).distinct()
        return (configured.flatMap { entry ->
            when (entry.lowercase()) {
                "popular" -> POPULAR
                "high-risk", "highrisk" -> HIGH_RISK
                "pseudo" -> PSEUDO
                "all", "shipped" -> shipped
                else -> listOf(entry)
            }
        } + RTL_PROBE).distinct()
    }
}
