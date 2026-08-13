package dev.stringfit

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Reads the string catalog and works out which entries are referenced anywhere.
 *
 * Reference scanning is deliberately lenient: a false "unused" claim is far
 * more damaging than a missed one, so anything that looks like a reference
 * counts.
 */
object Catalog {

    /**
     * `\w*R` also matches the R-class import aliases that multi-module apps use
     * with non-transitive R (`searchR.string.x`, `CoreUiR.string.x`). Missing
     * these cost 19 points of apparent coverage on Now in Android.
     */
    private val KOTLIN_REF = Regex("""\b\w*R\.(?:string|plurals|array)\.(\w+)""")
    private val XML_REF = Regex("""@(?:string|plurals|array)/(\w+)""")
    private val LINE_COMMENT = Regex("""//[^\n]*""")
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    fun parseStringsXml(file: File): List<StringEntry> {
        if (!file.isFile) return emptyList()
        val doc = runCatching {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(file)
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<StringEntry>()
        val children = doc.documentElement?.childNodes ?: return emptyList()
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (el.tagName !in setOf("string", "plurals", "string-array")) continue
            val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            out += StringEntry(
                name = name,
                value = el.textContent.orEmpty().trim(),
                translatable = el.getAttribute("translatable") != "false",
                kind = el.tagName,
                file = file.path,
            )
        }
        return out
    }

    /** Every `values/` catalog under the given resource directories (source locale only). */
    fun parseCatalog(resDirs: Collection<File>): List<StringEntry> =
        resDirs.filter { it.isDirectory }
            .flatMap { res -> res.listFiles()?.toList().orEmpty() }
            .filter { it.isDirectory && it.name == "values" }
            .flatMap { it.listFiles { f: File -> f.extension == "xml" }?.toList().orEmpty() }
            .flatMap(::parseStringsXml)
            .distinctBy { it.name }

    /** Resource names referenced from Kotlin/Java sources or from XML resources. */
    fun scanReferences(roots: Collection<File>): Set<String> {
        val found = mutableSetOf<String>()
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && !it.path.contains("${File.separator}build${File.separator}") }
                .forEach { f ->
                    when (f.extension) {
                        "kt", "java" -> {
                            val src = f.readText()
                                .replace(BLOCK_COMMENT, " ")
                                .replace(LINE_COMMENT, " ")
                            KOTLIN_REF.findAll(src).forEach { found += it.groupValues[1] }
                        }
                        "xml" -> XML_REF.findAll(f.readText())
                            .forEach { found += it.groupValues[1] }
                    }
                }
        }
        return found
    }

    /**
     * Triage file for unused strings. Minimal on purpose -- one line per entry:
     *
     * ```yaml
     * unused:
     *   legacy_hint: ignore        # gone in Q4
     *   paywall_title: translate   # feature ships next month
     *   share_email: keep
     * ```
     */
    fun parseTriage(file: File): Map<String, UnusedStatus> {
        if (!file.isFile) return emptyMap()
        val out = LinkedHashMap<String, UnusedStatus>()
        var inUnused = false
        file.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) return@forEach
            if (!line.first().isWhitespace()) {
                inUnused = line.trim().removeSuffix(":").trim() == "unused"
                return@forEach
            }
            if (!inUnused) return@forEach
            val name = line.substringBefore(':').trim()
            val status = line.substringAfter(':', "").trim().lowercase()
            if (name.isEmpty() || status.isEmpty()) return@forEach
            val parsed = when (status) {
                "ignore" -> UnusedStatus.IGNORE
                "translate" -> UnusedStatus.TRANSLATE
                "keep" -> UnusedStatus.KEEP
                else -> null
            }
            if (parsed != null) out[name] = parsed
        }
        return out
    }

    fun writeTriageBaseline(file: File, unused: List<String>, existing: Map<String, UnusedStatus>) {
        val merged = LinkedHashMap(existing)
        unused.forEach { merged.putIfAbsent(it, UnusedStatus.KEEP) }
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine("# StringFit triage for strings that are referenced nowhere.")
                appendLine("#   ignore    -- stop reporting it, do not send for translation")
                appendLine("#   translate -- unused today, but still translate it")
                appendLine("#   keep      -- keep reporting it (default; no decision made)")
                appendLine("unused:")
                merged.forEach { (name, status) ->
                    appendLine("  $name: ${status.name.lowercase()}")
                }
            }
        )
    }

    /** Measurement rows emitted by the generated harness (tab separated). */
    fun parseSites(dir: File): List<Site> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "tsv" }
            .flatMap { it.readLines().asSequence() }
            .mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 9 || p[0].isBlank()) return@mapNotNull null
                runCatching {
                    Site(
                        stringName = p[0],
                        preview = p[1],
                        maxWidthPx = p[2].toInt(),
                        intrinsicWidthPx = p[3].toInt(),
                        maxLines = p[4].toInt(),
                        linesNeeded = p[5].toInt(),
                        layoutWidthPx = p[6].toInt(),
                        widthDp = p[7].toInt(),
                        fontScale = p[8].toFloat(),
                    )
                }.getOrNull()
            }
            .toList()
    }
}
