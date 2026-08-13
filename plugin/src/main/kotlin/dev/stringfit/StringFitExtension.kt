package dev.stringfit

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

open class StringFitExtension
@Inject
constructor(objects: ObjectFactory) {
    /** Package tree the preview scanner walks. Defaults to the module namespace. */
    val packageTree: Property<String> = objects.property(String::class.java)

    /** Fully qualified R class, e.g. `com.example.app.R`. */
    val rClass: Property<String> = objects.property(String::class.java)

    /** Resource directories containing `values/`. */
    val resDirs: ConfigurableFileCollection = objects.fileCollection()

    /** Roots scanned for string references, used to detect unused strings. */
    val sourceDirs: ConfigurableFileCollection = objects.fileCollection()

    /** Fail the build when a string is cut off in the source language. */
    val failOnCutOff: Property<Boolean> = objects.property(Boolean::class.java)

    /** Triage file for unused strings. */
    val triageFile: RegularFileProperty = objects.fileProperty()

    /**
     * Languages to measure alongside the source locale.
     *
     * Empty (the default) means *every locale the project ships*, discovered
     * from `values-XX` directories -- measuring German on an app with no
     * German translation measures nothing. Presets `popular`, `high-risk`,
     * `pseudo` and `all` expand in place and mix with explicit codes.
     */
    val locales: ListProperty<String> = objects.listProperty(String::class.java)

    /** Add the harness test dependencies to Compose modules automatically. */
    val autoConfigureModules: Property<Boolean> = objects.property(Boolean::class.java)

    /** Compose BOM used for the test-only Compose artifacts. */
    val composeBom: Property<String> = objects.property(String::class.java)

    val robolectricVersion: Property<String> = objects.property(String::class.java)
    val previewScannerVersion: Property<String> = objects.property(String::class.java)

    internal fun applyConventions(project: Project) {
        resDirs.convention(project.files("src/main/res"))
        sourceDirs.convention(
            project.files("src/main/java", "src/main/kotlin", "src/main/res"),
        )
        triageFile.convention(project.layout.projectDirectory.file("stringfit.yml"))
        packageTree.convention(project.provider { androidNamespace(project) })
        rClass.convention(packageTree.map { "$it.R" })
        failOnCutOff.convention(false)
        autoConfigureModules.convention(true)
        composeBom.convention(DEFAULT_COMPOSE_BOM)
        robolectricVersion.convention(DEFAULT_ROBOLECTRIC)
        previewScannerVersion.convention(DEFAULT_PREVIEW_SCANNER)
    }

    /** Let a module fall back to whatever the root build configured. */
    internal fun inheritFrom(root: StringFitExtension) {
        if (root === this) return
        locales.convention(root.locales)
        failOnCutOff.convention(root.failOnCutOff)
        autoConfigureModules.convention(root.autoConfigureModules)
        composeBom.convention(root.composeBom)
        robolectricVersion.convention(root.robolectricVersion)
        previewScannerVersion.convention(root.previewScannerVersion)
    }

    private companion object {
        const val DEFAULT_COMPOSE_BOM = "androidx.compose:compose-bom:2025.04.00"
        const val DEFAULT_ROBOLECTRIC = "4.16.1"
        const val DEFAULT_PREVIEW_SCANNER = "0.9.2"

        /** Best-effort namespace lookup that does not bind us to one AGP version. */
        fun androidNamespace(project: Project): String {
            val android = project.extensions.findByName("android") ?: return project.name
            return runCatching {
                android.javaClass.getMethod("getNamespace").invoke(android) as? String
            }.getOrNull() ?: project.name
        }
    }
}
