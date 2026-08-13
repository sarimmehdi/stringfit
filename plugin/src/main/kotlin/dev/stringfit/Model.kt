package dev.stringfit

/** One translatable entry from `res/values`. */
data class StringEntry(
    val name: String,
    val value: String,
    val translatable: Boolean = true,
    val kind: String = "string",
    val file: String = "",
)

/**
 * One place a string was actually rendered, measured by the preview harness.
 *
 * [intrinsicWidthPx] is the width the text wants on a single line; it is the
 * only reliable basis for a truncation verdict. Compose's own overflow flags
 * (`didOverflowWidth`, `isLineEllipsized`) are unusable under Robolectric --
 * the first is inverted and the second never fires.
 */
data class Site(
    val stringName: String,
    val preview: String,
    val maxWidthPx: Int,
    val intrinsicWidthPx: Int,
    val maxLines: Int,
    val linesNeeded: Int,
    val layoutWidthPx: Int = 0,
    val widthDp: Int = 0,
    val fontScale: Float = 1f,
    /** Resource qualifier this was rendered under; empty means source locale. */
    val locale: String = "",
    /** Node bounds in the root, used to check that RTL layouts mirror. */
    val leftPx: Int = -1,
    val rightPx: Int = -1,
) {
    val rtl: Boolean get() = locale.isNotEmpty() && Locales.isRtl(locale)
}

/**
 * How much freedom a translator actually has at a site.
 *
 * Measured on a real app (Seal): 73% SOFT, 21% FREE, only 6% HARD. Reporting
 * this split is more useful than any character limit, because it tells a
 * translator where length matters at all.
 */
enum class SiteClass {
    /** Single line with a fixed width. Length is a hard constraint. */
    HARD,

    /** Wraps freely but is line-limited. The budget is lines, not width. */
    SOFT,

    /** Unbounded width. Length does not matter here. */
    FREE,
}

enum class UnusedStatus {
    /** Do not report, do not send for translation. */
    IGNORE,

    /** Unused today but should still be translated (feature not wired up yet). */
    TRANSLATE,

    /** Keep reporting it; no decision made. */
    KEEP,
}

data class StringVerdict(
    val name: String,
    val value: String,
    val klass: SiteClass,
    val sites: List<Site>,
    val cutOff: List<Site>,
    /** Width headroom (available / intrinsic) across HARD sites only. */
    val minWidthHeadroom: Double?,
    val maxWidthHeadroom: Double?,
    /** True when the same string faces very different budgets in different places. */
    val conflict: Boolean,
    /** Placeholder/mock copy, which should not be reported as a user-facing bug. */
    val looksLikeSample: Boolean,
    /** Locales in which at least one site is cut off. */
    val cutOffLocales: Set<String> = emptySet(),
) {
    val isCutOff: Boolean get() = cutOff.isNotEmpty()
}

/** Per-language rollup: what this translation does to the layout. */
data class LocaleSummary(
    val locale: String,
    val rtl: Boolean,
    val sites: Int,
    val cutOff: List<Site>,
    val tight: List<Site>,
    /** Mean intrinsic width relative to the source locale. >1 means expansion. */
    val expansion: Double?,
)

/**
 * A site whose available width changes between the LTR and RTL renders of the
 * same preview: the layout is not mirroring symmetrically.
 */
data class RtlAsymmetry(
    val stringName: String,
    val preview: String,
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        /** Available width changed when only the direction flipped. */
        WIDTH,

        /** The element stayed on the same side instead of mirroring. */
        POSITION,
    }
}

data class Report(
    val measured: List<StringVerdict>,
    /** Translatable, referenced somewhere, but never rendered by any preview. */
    val notMeasured: List<String>,
    /** Translatable and referenced nowhere at all. */
    val unused: List<String>,
    /** Unused entries the developer has already triaged. */
    val unusedTriaged: Map<String, UnusedStatus>,
    val catalogSize: Int,
    val locales: List<LocaleSummary> = emptyList(),
    val rtlAsymmetry: List<RtlAsymmetry> = emptyList(),
) {
    val cutOff: List<StringVerdict> get() = measured.filter { it.isCutOff && !it.looksLikeSample }
    val conflicts: List<StringVerdict> get() = measured.filter { it.conflict }
    val coveragePct: Double
        get() = if (catalogSize == 0) 0.0 else 100.0 * measured.size / catalogSize

    /** Strings worth sending to translation: everything except IGNORE-d unused ones. */
    fun translatable(catalog: List<StringEntry>): List<StringEntry> =
        catalog.filter { it.translatable }.filter {
            unusedTriaged[it.name] != UnusedStatus.IGNORE &&
                (it.name !in unused || unusedTriaged[it.name] == UnusedStatus.TRANSLATE)
        }
}
