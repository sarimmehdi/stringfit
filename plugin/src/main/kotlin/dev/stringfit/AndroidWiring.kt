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
        val deps = project.dependencies
        val configurations = project.configurations
        fun add(configuration: String, notation: Any) {
            configurations.findByName(configuration)?.dependencies?.add(deps.create(notation))
        }

        add("testImplementation", deps.platform(ext.composeBom.get()))
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
