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
            cutOffLocales = sites.filter { isCutOff(it) }.map { it.locale }.toSet(),
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
            locales = summarise(sites),
            rtlAsymmetry = rtlAsymmetry(sites),
        )
    }

    /** Mean intrinsic width per locale, relative to the source locale. */
    fun summarise(sites: List<Site>): List<LocaleSummary> {
        val source = sites.filter { it.locale.isEmpty() }
        val baseline = source.associate { (it.stringName to it.preview) to it.intrinsicWidthPx }
        return sites.groupBy { it.locale }
            .filterKeys { it.isNotEmpty() && !Locales.isProbe(it) }
            .map { (locale, group) ->
                val paired = group.mapNotNull { s ->
                    val base = baseline[s.stringName to s.preview]?.takeIf { it > 0 }
                    if (base == null) null else s.intrinsicWidthPx.toDouble() / base
                }
                LocaleSummary(
                    locale = locale,
                    rtl = Locales.isRtl(locale),
                    sites = group.size,
                    cutOff = group.filter { isCutOff(it) },
                    tight = group.filter {
                        !isCutOff(it) && (widthHeadroom(it) ?: 9.0) < TIGHT_HEADROOM
                    },
                    expansion = paired.average().takeIf { paired.isNotEmpty() },
                )
            }
            .sortedByDescending { it.cutOff.size }
    }

    /**
     * Compare each site's available width in an LTR render against its RTL
     * render. A material difference means the layout is not mirroring: usually
     * a hardcoded left/right padding or a `Modifier.padding(start=)` that was
     * meant to be direction-aware.
     */
    fun rtlAsymmetry(sites: List<Site>, tolerance: Double = 0.02): List<RtlAsymmetry> {
        // Only the direction probe is comparable to the source: it renders the
        // same text. A real RTL language differs in text as well as direction,
        // so a width change there says nothing about mirroring.
        val probe = sites.filter { Locales.isProbe(it.locale) }
        if (probe.isEmpty()) return emptyList()
        val ltr = sites.filter { it.locale.isEmpty() }
            .associateBy { it.stringName to it.preview }

        return probe.mapNotNull { r ->
            val l = ltr[r.stringName to r.preview] ?: return@mapNotNull null
            if (l.maxWidthPx <= 0 || r.maxWidthPx <= 0) return@mapNotNull null
            if (l.maxWidthPx >= UNBOUNDED_WIDTH_PX) return@mapNotNull null

            val widthDiff = kotlin.math.abs(l.maxWidthPx - r.maxWidthPx).toDouble() / l.maxWidthPx
            if (widthDiff > tolerance) {
                return@mapNotNull RtlAsymmetry(
                    r.stringName, r.preview, RtlAsymmetry.Kind.WIDTH,
                    "available width ${l.maxWidthPx}px LTR vs ${r.maxWidthPx}px RTL",
                )
            }

            // A mirrored element ends up as far from the right edge as its LTR
            // twin was from the left. `absolutePadding` and hardcoded left/right
            // insets keep the same width but never move, so only position
            // reveals them.
            val rootPx = l.widthDp * 2
            if (l.leftPx < 0 || r.leftPx < 0 || rootPx <= 0) return@mapNotNull null
            val expectedLeft = rootPx - l.rightPx
            val slack = (rootPx * tolerance).coerceAtLeast(4.0)
            if (kotlin.math.abs(r.leftPx - expectedLeft) > slack) {
                RtlAsymmetry(
                    r.stringName, r.preview, RtlAsymmetry.Kind.POSITION,
                    "sits at x=${r.leftPx}px in RTL; mirroring would place it at ${expectedLeft}px",
                )
            } else {
                null
            }
        }.distinctBy { it.stringName to it.preview }
    }
}
