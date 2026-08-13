package dev.stringfit

/**
 * Turns raw measurements into verdicts.
 *
 * Every rule here was corrected against a real app rather than reasoned out;
 * see the comments for what the naive version got wrong.
 */
object Budget {

    /** Compose reports unbounded constraints as a very large maxWidth. */
    const val UNBOUNDED_WIDTH_PX: Int = 1 shl 20

    /** `maxLines` left at its default reads as effectively infinite. */
    const val UNBOUNDED_LINES: Int = 1000

    /**
     * Ratio between the roomiest and tightest HARD site before a string is
     * flagged as conflicted. Real spreads on Seal topped out at 2.1x, so the
     * 3.0 threshold guessed during design would have fired on nothing.
     */
    const val CONFLICT_SPREAD: Double = 1.8

    /** Below this width headroom a HARD site is "tight" -- likely to break on translation. */
    const val TIGHT_HEADROOM: Double = 1.3

    private val SAMPLE_NAME = Regex(
        "(^|_)(sample|placeholder|dummy|mock|lorem|preview|example)(_|$)",
        RegexOption.IGNORE_CASE,
    )

    fun classify(site: Site): SiteClass = when {
        site.maxWidthPx >= UNBOUNDED_WIDTH_PX -> SiteClass.FREE
        site.maxLines > UNBOUNDED_LINES -> SiteClass.SOFT
        site.maxLines == 1 -> SiteClass.HARD
        else -> SiteClass.SOFT
    }

    /**
     * Was the text actually cut off?
     *
     * For multi-line text `intrinsic > maxWidth` is *normal* -- it just wraps.
     * Including that condition (as the first draft did) reported 6 broken
     * strings on Seal where only 1 was real.
     */
    fun isCutOff(site: Site): Boolean = when (classify(site)) {
        SiteClass.FREE -> false
        SiteClass.HARD -> site.intrinsicWidthPx > site.maxWidthPx
        SiteClass.SOFT -> site.maxLines in 1..UNBOUNDED_LINES &&
            site.linesNeeded > site.maxLines
    }

    /**
     * Available width over wanted width. Only meaningful for HARD sites: for
     * wrapping text a ratio below 1.0 just means "it wraps", not "it breaks".
     * 2.0 means the translation can double in width before it truncates.
     */
    fun widthHeadroom(site: Site): Double? {
        if (classify(site) != SiteClass.HARD) return null
        if (site.intrinsicWidthPx <= 0) return null
        return site.maxWidthPx.toDouble() / site.intrinsicWidthPx
    }

    /** Allowed lines over needed lines. The SOFT-site equivalent of width headroom. */
    fun lineHeadroom(site: Site): Double? {
        if (classify(site) != SiteClass.SOFT) return null
        if (site.linesNeeded <= 0 || site.maxLines > UNBOUNDED_LINES) return null
        return site.maxLines.toDouble() / site.linesNeeded
    }

    fun looksLikeSample(entry: StringEntry): Boolean =
        SAMPLE_NAME.containsMatchIn(entry.name) ||
            entry.value.startsWith("Lorem ipsum", ignoreCase = true)

    fun verdict(entry: StringEntry, sites: List<Site>): StringVerdict {
        val hard = sites.mapNotNull { widthHeadroom(it) }
        val tightest = sites.minByOrNull { classify(it).ordinal }?.let { classify(it) }
            ?: SiteClass.FREE
        val distinctSites = sites.map { it.preview to it.maxWidthPx }.distinct().size
        val conflict = hard.size >= 2 && distinctSites >= 2 &&
            (hard.max() / hard.min()) >= CONFLICT_SPREAD
        return StringVerdict(
            name = entry.name,
            value = entry.value,
            klass = tightest,
            sites = sites,
            cutOff = sites.filter { isCutOff(it) },
            minWidthHeadroom = hard.minOrNull(),
            maxWidthHeadroom = hard.maxOrNull(),
            conflict = conflict,
            looksLikeSample = looksLikeSample(entry),
        )
    }

    fun analyze(
        catalog: List<StringEntry>,
        sites: List<Site>,
        referenced: Set<String>,
        triaged: Map<String, UnusedStatus> = emptyMap(),
    ): Report {
        val translatable = catalog.filter { it.translatable }
        val byName = sites.groupBy { it.stringName }
        val measured = translatable
            .filter { byName.containsKey(it.name) }
            .map { Budget.verdict(it, byName.getValue(it.name)) }
            .sortedWith(compareBy({ it.minWidthHeadroom ?: Double.MAX_VALUE }, { it.name }))
        val measuredNames = measured.map { it.name }.toSet()
        val unused = translatable.map { it.name }.filter { it !in referenced }.sorted()
        val notMeasured = translatable.map { it.name }
            .filter { it !in measuredNames && it in referenced }.sorted()
        return Report(
            measured = measured,
            notMeasured = notMeasured,
            unused = unused,
            unusedTriaged = triaged,
            catalogSize = translatable.size,
        )
    }
}
