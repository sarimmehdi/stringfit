package dev.stringfit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.Serializable

/** Everything the root needs to know about one module, captured for the task graph. */
data class ModuleSpec(
    val path: String,
    val packageTree: String,
    val rClasses: List<String>,
    val harnessFile: File,
    val sourceDirs: List<File>,
) : Serializable {
    /** Cheap test for "is there any Compose UI worth measuring here". */
    fun hasPreviews(): Boolean = sourceDirs.asSequence()
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown() }
        .filter { it.isFile && it.extension == "kt" }
        .any { it.readText().contains("@Preview") }

    companion object {
        private const val serialVersionUID = 1L
    }
}

@DisableCachingByDefault(because = "Writes a source file the developer owns")
abstract class InstallHarnessTask : DefaultTask() {
    @get:Input abstract val packageTree: Property<String>

    @get:Input abstract val rClasses: ListProperty<String>

    @get:OutputFile abstract val target: RegularFileProperty

    @set:Option(option = "overwrite", description = "Replace an existing harness file")
    @get:Input
    var overwrite: Boolean = false

    @TaskAction
    fun run() {
        val file = target.get().asFile
        if (file.exists() && !overwrite) {
            logger.lifecycle("StringFit: harness already at ${file.path} (--overwrite to replace)")
            return
        }
        file.parentFile.mkdirs()
        file.writeText(Harness.source(packageTree.get(), rClasses.get()))
        logger.lifecycle("StringFit: wrote ${file.path}")
    }
}

/** Root-level installer: puts a harness in every module that has previews. */
@DisableCachingByDefault(because = "Writes source files the developer owns")
abstract class InstallAllTask : DefaultTask() {
    @get:Input abstract val modules: ListProperty<ModuleSpec>

    @set:Option(option = "overwrite", description = "Replace existing harness files")
    @get:Input
    var overwrite: Boolean = false

    @TaskAction
    fun run() {
        val candidates = modules.get().filter { it.hasPreviews() }
        if (candidates.isEmpty()) {
            logger.lifecycle(
                "StringFit: no module contains @Preview functions; nothing to install.",
            )
            return
        }
        var written = 0
        candidates.forEach { spec ->
            if (spec.harnessFile.exists() && !overwrite) {
                logger.lifecycle("StringFit: ${spec.path} already has a harness, skipping.")
                return@forEach
            }
            spec.harnessFile.parentFile.mkdirs()
            spec.harnessFile.writeText(Harness.source(spec.packageTree, spec.rClasses))
            logger.lifecycle("StringFit: ${spec.path} -> ${spec.harnessFile.path}")
            written++
        }
        val skipped = modules.get().size - candidates.size
        logger.lifecycle(
            "StringFit: installed $written harness file(s); " +
                "$skipped module(s) had no previews. Re-sync, then run `test`.",
        )
    }
}

@DisableCachingByDefault(because = "Writes a tiny file consumed by the test task")
abstract class PrepareTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirs: ConfigurableFileCollection

    @get:Input abstract val locales: ListProperty<String>

    @get:Input abstract val quiet: Property<Boolean>

    @get:OutputFile abstract val localesFile: RegularFileProperty

    @TaskAction
    fun run() {
        val resolved = Locales.resolve(locales.get(), resDirs.files)
        val file = localesFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(resolved.joinToString("\n"))
        if (quiet.getOrElse(false)) return

        val languages = resolved.filterNot(Locales::isProbe)
        if (languages.isEmpty()) {
            logger.lifecycle(
                "StringFit: no translated locales found; measuring the source locale only. " +
                    "Add values-XX resources, or set locales = listOf(\"pseudo\").",
            )
        } else {
            val rtl = languages.filter(Locales::isRtl)
            val suffix = if (rtl.isEmpty()) "" else "  (RTL: ${rtl.joinToString(", ")})"
            logger.lifecycle(
                "StringFit: measuring ${languages.size} locale(s): " +
                    languages.joinToString(", ") + suffix,
            )
        }
    }
}

@DisableCachingByDefault(because = "Writes a file the developer edits by hand")
abstract class BaselineTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val sourceDirs: ConfigurableFileCollection

    @get:OutputFile abstract val triageFile: RegularFileProperty

    @TaskAction
    fun run() {
        val catalog = Catalog.parseCatalog(resDirs.files)
        val referenced = Catalog.scanReferences(sourceDirs.files)
        val file = triageFile.get().asFile
        val existing = Catalog.parseTriage(file)
        val unused = catalog.filter { it.translatable && it.name !in referenced }.map { it.name }
        Catalog.writeTriageBaseline(file, unused, existing)
        logger.lifecycle(
            "StringFit: ${unused.size} unused string(s) written to ${file.path}. " +
                "Mark each ignore / translate / keep.",
        )
    }
}

@DisableCachingByDefault(because = "Cheap to recompute and always logs its report")
abstract class ReportTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val sourceDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val siteDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val triageFile: RegularFileProperty

    @get:Input abstract val failOnCutOff: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val catalog = Catalog.parseCatalog(resDirs.files)
        if (catalog.isEmpty()) {
            logger.warn("StringFit: no strings found. Check stringFit { resDirs }.")
        }
        val sites = siteDirs.files.flatMap(Catalog::parseSites)
        val referenced = Catalog.scanReferences(sourceDirs.files)
        val triage = triageFile.orNull?.asFile?.let(Catalog::parseTriage).orEmpty()
        val report = Budget.analyze(catalog, sites, referenced, triage)

        val out = outputDir.get().asFile.apply { mkdirs() }
        val text = ReportRenderer.text(report)
        File(out, "report.txt").writeText(text)
        File(out, "strings.tsv").writeText(ReportRenderer.tsv(report))
        logger.lifecycle(text)
        logger.lifecycle("StringFit: wrote ${File(out, "report.txt").path}")

        if (sites.isEmpty()) {
            logger.warn(
                "StringFit: no measurements found. Install the harness " +
                    "(stringFitInstallHarness) and run the unit tests first.",
            )
        }
        if (failOnCutOff.get() && report.cutOff.isNotEmpty()) {
            throw GradleException(
                "StringFit: ${report.cutOff.size} string(s) are cut off in the source language.",
            )
        }
    }
}
