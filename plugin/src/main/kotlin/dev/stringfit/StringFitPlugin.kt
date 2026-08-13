package dev.stringfit

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
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

open class StringFitExtension @Inject constructor(objects: ObjectFactory) {
    /** Package tree the preview scanner walks. Defaults to the module namespace. */
    val packageTree: Property<String> = objects.property(String::class.java)

    /** Fully qualified R class, e.g. `com.example.app.R`. */
    val rClass: Property<String> = objects.property(String::class.java)

    /** Resource directories containing `values/`. */
    val resDirs: ConfigurableFileCollection = objects.fileCollection()

    /** Roots scanned for string references, used to detect unused strings. */
    val sourceDirs: ConfigurableFileCollection = objects.fileCollection()

    /** Fail the build when a string is cut off in the source language. */
    val failOnCutOff: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Triage file for unused strings. */
    val triageFile: RegularFileProperty = objects.fileProperty()
}

@DisableCachingByDefault(because = "Writes a source file the developer owns")
abstract class InstallHarnessTask : DefaultTask() {
    @get:Input abstract val packageTree: Property<String>
    @get:Input abstract val rClass: Property<String>
    @get:OutputFile abstract val target: RegularFileProperty

    @set:Option(option = "overwrite", description = "Replace an existing harness file")
    @get:Input var overwrite: Boolean = false

    @TaskAction
    fun run() {
        val file = target.get().asFile
        if (file.exists() && !overwrite) {
            logger.lifecycle("StringFit: harness already present at ${file.path} (--overwrite to replace)")
            return
        }
        file.parentFile.mkdirs()
        file.writeText(Harness.source(packageTree.get(), rClass.get()))
        logger.lifecycle(
            """
            |StringFit: wrote ${file.path}
            |
            |Add to this module's build.gradle.kts:
            |
            ${Harness.REQUIRED_ANDROID_CONFIG.prependIndent("            |  ")}
            |
            |dependencies {
            ${Harness.REQUIRED_TEST_DEPENDENCIES.joinToString("\n") { "            |    $it" }}
            |}
            |
            |Then: ./gradlew test && ./gradlew stringFitReport
            """.trimMargin()
        )
    }
}

@DisableCachingByDefault(because = "Cheap to recompute and always logs its report")
abstract class ReportTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirs: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:Optional
    abstract val sourceDirs: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:Optional
    abstract val sitesDir: DirectoryProperty

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:Optional
    abstract val triageFile: RegularFileProperty
    @get:Input abstract val failOnCutOff: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val catalog = Catalog.parseCatalog(resDirs.files)
        if (catalog.isEmpty()) {
            logger.warn("StringFit: no strings found. Check stringFit { resDirs }.")
        }
        val sites = sitesDir.orNull?.asFile?.let(Catalog::parseSites).orEmpty()
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
                "StringFit: no measurements found. Run the unit tests that contain " +
                    "StringFitHarnessTest first (./gradlew test)."
            )
        }
        if (failOnCutOff.get() && report.cutOff.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "StringFit: ${report.cutOff.size} string(s) are cut off in the source language."
            )
        }
    }
}

@DisableCachingByDefault(because = "Writes a file the developer edits by hand")
abstract class BaselineTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirs: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:Optional
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
                "Mark each ignore / translate / keep."
        )
    }
}

class StringFitPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("stringFit", StringFitExtension::class.java)
        val layout = project.layout

        ext.resDirs.convention(project.files("src/main/res"))
        ext.sourceDirs.convention(project.files("src/main/java", "src/main/kotlin", "src/main/res"))
        ext.triageFile.convention(layout.projectDirectory.file("stringfit.yml"))
        ext.packageTree.convention(project.provider { defaultPackage(project) })
        ext.rClass.convention(ext.packageTree.map { "$it.R" })

        project.tasks.register("stringFitInstallHarness", InstallHarnessTask::class.java) { t ->
            t.group = GROUP
            t.description = "Installs the preview measurement harness into src/test."
            t.packageTree.set(ext.packageTree)
            t.rClass.set(ext.rClass)
            t.target.set(layout.projectDirectory.file(Harness.RELATIVE_PATH))
        }

        project.tasks.register("stringFitBaseline", BaselineTask::class.java) { t ->
            t.group = GROUP
            t.description = "Records currently-unused strings so only new ones are reported."
            t.resDirs.from(ext.resDirs)
            t.sourceDirs.from(ext.sourceDirs)
            t.triageFile.set(ext.triageFile)
        }

        project.tasks.register("stringFitReport", ReportTask::class.java) { t ->
            t.group = GROUP
            t.description = "Reports per-string width and line budgets from preview measurements."
            t.resDirs.from(ext.resDirs)
            t.sourceDirs.from(ext.sourceDirs)
            t.sitesDir.set(layout.buildDirectory.dir("stringfit/sites"))
            t.triageFile.set(ext.triageFile)
            t.failOnCutOff.set(ext.failOnCutOff)
            t.outputDir.set(layout.buildDirectory.dir("reports/stringfit"))
        }
    }

    /** Best-effort namespace lookup that does not bind us to one AGP version. */
    private fun defaultPackage(project: Project): String {
        val android = project.extensions.findByName("android") ?: return project.name
        return runCatching {
            android.javaClass.getMethod("getNamespace").invoke(android) as? String
        }.getOrNull() ?: project.name
    }

    private companion object {
        const val GROUP = "stringfit"
    }
}
