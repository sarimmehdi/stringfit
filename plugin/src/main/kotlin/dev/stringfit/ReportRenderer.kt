package dev.stringfit

object ReportRenderer {
    /** How many lines of any one section to print before summarising the rest. */
    private const val MAX_ROWS = 20

    fun text(report: Report): String = buildString {
        summary(report)
        languages(report)
        findings(report)
        notMeasured(report)
    }

    private fun StringBuilder.summary(report: Report) {
        appendLine("StringFit")
        appendLine("=========")
        appendLine()
        appendLine("Catalog          ${report.catalogSize} translatable strings")
        appendLine(
            "Measured         ${report.measured.size} " +
                "(${"%.1f".format(report.coveragePct)}%) rendered by at least one @Preview",
        )
        appendLine("Not measured     ${report.notMeasured.size} referenced but never rendered")
        appendLine("Unused           ${report.unused.size} referenced nowhere")
        appendLine()

        val sites = report.measured.flatMap { it.sites }
        val byClass = sites.groupingBy { Budget.classify(it) }.eachCount()
        appendLine(
            "Site classes     " +
                SiteClass.entries.joinToString("  ") {
                    "${it.name.lowercase()}=${byClass[it] ?: 0}"
                },
        )
        if (sites.isNotEmpty()) {
            val hardPct = 100.0 * (byClass[SiteClass.HARD] ?: 0) / sites.size
            appendLine(
                "                 only ${"%.0f".format(hardPct)}% of sites constrain length at all",
            )
        }
        appendLine()
    }

    private fun StringBuilder.languages(report: Report) {
        if (report.locales.isEmpty()) return
        appendLine("Languages")
        appendLine("  %-10s %-5s %7s %8s %8s".format("locale", "dir", "sites", "expand", "cut off"))
        report.locales.forEach { l ->
            appendLine(
                "  %-10s %-5s %7d %8s %8d".format(
                    l.locale,
                    if (l.rtl) "RTL" else "LTR",
                    l.sites,
                    l.expansion?.let { "%.2fx".format(it) } ?: "-",
                    l.cutOff.size,
                ),
            )
        }
        appendLine()
    }

    private fun StringBuilder.findings(report: Report) {
        report.locales.filter { it.cutOff.isNotEmpty() }.forEach { l ->
            section(
                "CUT OFF in ${l.locale}${if (l.rtl) " (RTL)" else ""}",
                l.cutOff.distinctBy { it.stringName }.map {
                    "${it.stringName}  needs ${it.intrinsicWidthPx}px in ${it.maxWidthPx}px"
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

        section(
            "CUT OFF — text does not fit today",
            report.cutOff.map { v ->
                val w = v.cutOff.first()
                val lines =
                    if (w.maxLines in 2..Budget.UNBOUNDED_LINES) {
                        "  (${w.linesNeeded} lines, max ${w.maxLines})"
                    } else {
                        ""
                    }
                "${v.name}  needs ${w.intrinsicWidthPx}px in ${w.maxWidthPx}px$lines"
            },
        )

        section(
            "TIGHT — under ${Budget.TIGHT_HEADROOM}x headroom, likely to break when translated",
            report.measured
                .filter {
                    !it.isCutOff &&
                        (it.minWidthHeadroom ?: Double.MAX_VALUE) < Budget.TIGHT_HEADROOM
                }
                .map { "${it.name}  ${"%.2f".format(it.minWidthHeadroom)}x headroom" },
        )

        section(
            "CONFLICT — one string, very different budgets",
            report.conflicts.map {
                "${it.name}  ${"%.2f".format(it.minWidthHeadroom)}x .. " +
                    "${"%.2f".format(it.maxWidthHeadroom)}x across ${it.sites.size} sites"
            },
        )

        section(
            "UNUSED — referenced nowhere; triage in stringfit.yml",
            report.unused.filter {
                report.unusedTriaged[it].let { s -> s == null || s == UnusedStatus.KEEP }
            },
        )
    }

    private fun StringBuilder.notMeasured(report: Report) {
        if (report.notMeasured.isEmpty()) return
        appendLine("NOT MEASURED (${report.notMeasured.size})")
        appendLine("  These are used in your app but no @Preview renders them.")
        appendLine("  Writing a preview for each is what grows coverage.")
        report.notMeasured.take(MAX_ROWS).forEach { appendLine("    $it") }
        if (report.notMeasured.size > MAX_ROWS) {
            appendLine("    ... and ${report.notMeasured.size - MAX_ROWS} more")
        }
        appendLine()
    }

    private fun StringBuilder.section(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine("$title (${lines.size})")
        lines.take(MAX_ROWS).forEach { appendLine("    $it") }
        if (lines.size > MAX_ROWS) appendLine("    ... and ${lines.size - MAX_ROWS} more")
        appendLine()
    }

    /** Flat export, ready to hand to a translator or a TMS. */
    fun tsv(report: Report): String = buildString {
        appendLine(
            "name\tclass\tsites\tmin_width_headroom\tmax_width_headroom\tcut_off\tconflict",
        )
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
                ).joinToString("\t"),
            )
        }
    }
}
