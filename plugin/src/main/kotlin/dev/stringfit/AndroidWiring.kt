package dev.stringfit

import org.gradle.api.Project

/**
 * Touches the Android extension reflectively.
 *
 * The plugin deliberately does not compile against a specific AGP version: it
 * has to work across the AGP 8 and AGP 9 lines, whose DSL types differ. Every
 * call here degrades to a warning rather than failing the build.
 */
internal object AndroidWiring {
    private const val BOM_GROUP = "androidx.compose"
    private const val BOM_NAME = "compose-bom"

    /**
     * Reuse whatever Compose BOM the module already builds against.
     *
     * A hardcoded default is worse than useless here: it silently adds a second,
     * conflicting platform to the test classpath, and the mismatch surfaces much
     * later as a confusing Compose runtime error.
     */
    private fun detectComposeBom(project: Project): String? = MAIN_CONFIGURATIONS
        .asSequence()
        .mapNotNull { project.configurations.findByName(it) }
        .flatMap { it.allDependencies.asSequence() }
        .firstOrNull { it.group == BOM_GROUP && it.name == BOM_NAME && it.version != null }
        ?.let { "$BOM_GROUP:$BOM_NAME:${it.version}" }

    private val MAIN_CONFIGURATIONS =
        listOf("implementation", "api", "compileOnly", "debugImplementation")

    /**
     * Robolectric cannot resolve resources without this, and the harness reads
     * every string out of the compiled resource table.
     */
    fun includeAndroidResourcesInUnitTests(project: Project) {
        val android = project.extensions.findByName("android") ?: return
        runCatching {
            val testOptions = android.javaClass.getMethod("getTestOptions").invoke(android)
            val unitTests = testOptions.javaClass.getMethod("getUnitTests").invoke(testOptions)
            unitTests.javaClass
                .getMethod("setIncludeAndroidResources", Boolean::class.javaPrimitiveType)
                .invoke(unitTests, true)
        }.onFailure {
            project.logger.warn(
                "StringFit: could not set testOptions.unitTests.isIncludeAndroidResources " +
                    "on ${project.path}; set it by hand or the harness will find no strings.",
            )
        }
    }

    /**
     * Add what the harness needs to compile and run, so a module never has to
     * be edited by hand.
     */
    fun addHarnessDependencies(project: Project, ext: StringFitExtension) {
        if (!ext.autoConfigureModules.getOrElse(true)) return
        val bom = ext.composeBom.orNull ?: detectComposeBom(project)
        if (bom == null) {
            project.logger.warn(
                "StringFit: ${project.path} builds Compose but declares no Compose BOM, so the " +
                    "harness cannot pin its test-only Compose artifacts. Set " +
                    "stringFit { composeBom = \"androidx.compose:compose-bom:<version>\" }.",
            )
            return
        }
        val deps = project.dependencies
        val configurations = project.configurations
        fun add(configuration: String, notation: Any) {
            configurations.findByName(configuration)?.dependencies?.add(deps.create(notation))
        }

        add("testImplementation", deps.platform(bom))
        add("testImplementation", "org.robolectric:robolectric:${ext.robolectricVersion.get()}")
        add("testImplementation", "androidx.test:core:1.6.1")
        add("testImplementation", "androidx.compose.ui:ui-test-junit4")
        add("testImplementation", "junit:junit:4.13.2")
        add(
            "testImplementation",
            "io.github.sergio-sastre.ComposablePreviewScanner:android:" +
                ext.previewScannerVersion.get(),
        )
        // Provides the ComponentActivity the harness launches into.
        add("debugImplementation", "androidx.compose.ui:ui-test-manifest")
    }
}
