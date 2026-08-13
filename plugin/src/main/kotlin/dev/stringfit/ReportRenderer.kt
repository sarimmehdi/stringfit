package dev.stringfit

object ReportRenderer {

    fun text(report: Report): String = buildString {
        val m = report.measured
        appendLine("StringFit")
        appendLine("=========")
        appendLine()
        appendLine("Catalog          ${report.catalogSize} translatable strings")
        appendLine(
            "Measured         ${m.size} (${"%.1f".format(report.coveragePct)}%) " +
                "rendered by at least one @Preview"
        )
        appendLine("Not measured     ${report.notMeasured.size} referenced but never rendered")
        appendLine("Unused           ${report.unused.size} referenced nowhere")
        appendLine()

        val sites = m.flatMap { it.sites }
        val byClass = sites.groupingBy { Budget.classify(it) }.eachCount()
        val hard = byClass[SiteClass.HARD] ?: 0
        appendLine("Site classes     " + SiteClass.entries.joinToString("  ") {
            "${it.name.lowercase()}=${byClass[it] ?: 0}"
        })
        if (sites.isNotEmpty()) {
            appendLine(
                "                 only ${"%.0f".format(100.0 * hard / sites.size)}% of sites " +
                    "constrain length at all"
            )
        }
        appendLine()

        if (report.locales.isNotEmpty()) {
            appendLine("Languages")
            appendLine(
                "  %-10s %-5s %7s %8s %8s".format("locale", "dir", "sites", "expand", "cut off")
            )
            report.locales.forEach { l ->
                appendLine(
                    "  %-10s %-5s %7d %8s %8d".format(
                        l.locale,
                        if (l.rtl) "RTL" else "LTR",
                        l.sites,
                        l.expansion?.let { "%.2fx".format(it) } ?: "-",
                        l.cutOff.size,
                    )
                )
            }
            appendLine()
        }

        report.locales.filter { it.cutOff.isNotEmpty() }.forEach { l ->
            section(
                "CUT OFF in ${l.locale}${if (l.rtl) " (RTL)" else ""}",
                l.cutOff.distinctBy { it.stringName }.map { s ->
                    "${s.stringName}  needs ${s.intrinsicWidthPx}px in ${s.maxWidthPx}px"
                },
            )
        }

        section(
            "RTL ASYMMETRY — layout does not mirror; check start/end padding",
            report.rtlAsymmetry.map {
                "${it.stringName} @ ${it.preview.substringAfterLast('.')}  " +
                    "[${it.kind.name.lowercase()}] ${it.detail}"
            },
        )

        section("CUT OFF — text does not fit today", report.cutOff.map { v ->
            val w = v.cutOff.first()
            "${v.name}  needs ${w.intrinsicWidthPx}px in ${w.maxWidthPx}px" +
                if (w.maxLines in 2..Budget.UNBOUNDED_LINES)
                    "  (${w.linesNeeded} lines, max ${w.maxLines})" else ""
        })

        section("TIGHT — under ${Budget.TIGHT_HEADROOM}x headroom, likely to break when translated",
            m.filter { !it.isCutOff && (it.minWidthHeadroom ?: 9.0) < Budget.TIGHT_HEADROOM }
                .map { "${it.name}  ${"%.2f".format(it.minWidthHeadroom)}x headroom" })

        section("CONFLICT — one string, very different budgets", report.conflicts.map {
            "${it.name}  ${"%.2f".format(it.minWidthHeadroom)}x .. " +
                "${"%.2f".format(it.maxWidthHeadroom)}x across ${it.sites.size} sites"
        })

        val undecided = report.unused.filter {
            report.unusedTriaged[it] == null || report.unusedTriaged[it] == UnusedStatus.KEEP
        }
        section("UNUSED — referenced nowhere; triage in stringfit.yml", undecided)

        if (report.notMeasured.isNotEmpty()) {
            appendLine("NOT MEASURED (${report.notMeasured.size})")
            appendLine("  These are used in your app but no @Preview renders them.")
            appendLine("  Writing a preview for each is what grows coverage.")
            report.notMeasured.take(15).forEach { appendLine("    $it") }
            if (report.notMeasured.size > 15) {
                appendLine("    ... and ${report.notMeasured.size - 15} more")
            }
            appendLine()
        }
    }

    private fun StringBuilder.section(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine("$title (${lines.size})")
        lines.take(20).forEach { appendLine("    $it") }
        if (lines.size > 20) appendLine("    ... and ${lines.size - 20} more")
        appendLine()
    }

    /** Flat export, ready to hand to a translator or a TMS. */
    fun tsv(report: Report): String = buildString {
        appendLine("name\tclass\tsites\tmin_width_headroom\tmax_width_headroom\tcut_off\tconflict")
        report.measured.forEach { v ->
            appendLine(
                listOf(
                    v.name,
                    v.klass.name.lowercase(),
                    v.sites.size,
                    v.minWidthHeadroom?.let { "%.3f".format(it) } ?: "",
                    v.maxWidthHeadroom?.let { "%.3f".format(it) } ?: "",
                    v.isCutOff,
                    v.conflict,
                ).joinToString("\t")
            )
        }
    }
}
