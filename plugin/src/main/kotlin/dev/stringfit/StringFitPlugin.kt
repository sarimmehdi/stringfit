package dev.stringfit

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/**
 * Apply once, in the root `build.gradle.kts`:
 *
 * ```kotlin
 * plugins { id("io.github.sarimmehdi.stringfit") }
 * ```
 *
 * Every Android module is then configured automatically -- test dependencies,
 * unit-test resources and per-module tasks -- and the root gets aggregate tasks
 * that report across the whole build. Aggregation matters: a string declared in
 * `:core:ui` is usually rendered by a preview in `:feature:home`, and neither
 * module can judge it alone.
 *
 * Applying it to a single module also works and configures just that module.
 */
class StringFitPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val root = target.extensions.create("stringFit", StringFitExtension::class.java)
        root.applyConventions(target)

        if (target == target.rootProject) {
            target.subprojects { sub ->
                whenAndroid(
                    sub,
                ) { configureModule(sub, root, standalone = false) }
            }
            target.afterEvaluate { registerAggregateTasks(target, root) }
        } else {
            whenAndroid(target) { configureModule(target, root, standalone = true) }
        }
    }

    private fun whenAndroid(project: Project, action: () -> Unit) {
        var done = false
        ANDROID_PLUGINS.forEach { id ->
            project.plugins.withId(id) {
                if (!done) {
                    done = true
                    action()
                }
            }
        }
    }

    private fun configureModule(module: Project, root: StringFitExtension, standalone: Boolean) {
        val local =
            module.extensions.findByType(StringFitExtension::class.java)
                ?: module.extensions.create("stringFit", StringFitExtension::class.java)
        local.applyConventions(module)
        local.inheritFrom(root)

        AndroidWiring.includeAndroidResourcesInUnitTests(module)
        registerModuleTasks(module, local, root, standalone)
        if (!standalone) contributeToAggregate(module, local)

        // Dependencies are declared after this plugin is applied, and the
        // Compose BOM has to be read from them, so wire the harness stack once
        // the module's own build script has run.
        module.afterEvaluate { evaluated ->
            val buildsCompose = evaluated.plugins.hasPlugin(COMPOSE_PLUGIN)
            val hasHarness = evaluated.file(Harness.RELATIVE_PATH).isFile
            if (buildsCompose || hasHarness) {
                AndroidWiring.addHarnessDependencies(evaluated, local)
            }
        }
    }

    private fun registerModuleTasks(
        module: Project,
        ext: StringFitExtension,
        root: StringFitExtension,
        standalone: Boolean,
    ) {
        val layout = module.layout

        // When the plugin is applied at the root, the root owns the install and
        // report task names. Registering them per module as well would make
        // `gradlew stringFitInstallHarness` run in every project and bypass the
        // aggregate task's "does this module have previews" gate.
        if (standalone) {
            module.tasks.register("stringFitInstallHarness", InstallHarnessTask::class.java) { t ->
                t.group = GROUP
                t.description = "Installs the preview measurement harness into src/test."
                t.packageTree.set(ext.packageTree)
                t.rClasses.set(allRClasses(module))
                t.target.set(layout.projectDirectory.file(Harness.RELATIVE_PATH))
            }
            module.tasks.register("stringFitReport", ReportTask::class.java) { t ->
                t.group = GROUP
                t.description = "Reports width and line budgets for this module."
                t.resDirs.from(ext.resDirs)
                t.sourceDirs.from(ext.sourceDirs)
                t.siteDirs.from(layout.buildDirectory.dir("stringfit/sites"))
                t.triageFile.set(ext.triageFile)
                t.failOnCutOff.set(ext.failOnCutOff)
                t.outputDir.set(layout.buildDirectory.dir("reports/stringfit"))
            }
            module.tasks.register("stringFitBaseline", BaselineTask::class.java) { t ->
                t.group = GROUP
                t.description = "Records currently-unused strings for this module."
                t.resDirs.from(ext.resDirs)
                t.sourceDirs.from(ext.sourceDirs)
                t.triageFile.set(ext.triageFile)
            }
        }

        val prepare =
            module.tasks.register("stringFitPrepare", PrepareTask::class.java) { t ->
                t.group = GROUP
                t.description = "Resolves which locales the harness should measure."
                t.resDirs.from(ext.resDirs)
                t.locales.set(root.locales)
                t.quiet.set(!standalone)
                t.localesFile.set(layout.buildDirectory.file("stringfit/locales.txt"))
            }

        // The harness reads locales.txt at test time, so it must exist first --
        // but only modules that actually have a harness should pay for it.
        // Installing one changes this decision, which is why the install task
        // asks for a re-sync.
        if (module.file(Harness.RELATIVE_PATH).isFile) {
            module.tasks.withType(Test::class.java).configureEach { task ->
                if (task.name.contains("Release", ignoreCase = true)) {
                    // androidx.compose.ui:ui-test-manifest is a debug-only
                    // dependency, so the activity the harness launches into is
                    // missing from a release variant's merged manifest. Running
                    // `gradlew test` would otherwise fail on the release task.
                    task.exclude("stringfit/**")
                } else {
                    task.dependsOn(prepare)
                }
            }
        }
    }

    /** Feed one module's resources, sources and measurements to the root tasks. */
    private fun contributeToAggregate(module: Project, ext: StringFitExtension) {
        val root = module.rootProject
        root.tasks.named(REPORT, ReportTask::class.java) { t ->
            t.resDirs.from(ext.resDirs)
            t.sourceDirs.from(ext.sourceDirs)
            t.siteDirs.from(module.layout.buildDirectory.dir("stringfit/sites"))
        }
        root.tasks.named(BASELINE, BaselineTask::class.java) { t ->
            t.resDirs.from(ext.resDirs)
            t.sourceDirs.from(ext.sourceDirs)
        }
        root.tasks.named(INSTALL, InstallAllTask::class.java) { t ->
            t.modules.add(module.provider { describeModule(module) })
        }
    }

    private fun registerAggregateTasks(root: Project, ext: StringFitExtension) {
        root.tasks.register(INSTALL, InstallAllTask::class.java) { t ->
            t.group = GROUP
            t.description = "Installs the harness into every module that has @Preview functions."
        }

        root.tasks.register(BASELINE, BaselineTask::class.java) { t ->
            t.group = GROUP
            t.description = "Records currently-unused strings across all modules."
            t.resDirs.from(ext.resDirs)
            t.sourceDirs.from(ext.sourceDirs)
            t.triageFile.set(ext.triageFile)
        }

        root.tasks.register(REPORT, ReportTask::class.java) { t ->
            t.group = GROUP
            t.description = "Reports width and line budgets across every module."
            // The root's own directories are included too. They are empty in a
            // normal Android build, and they let a single-project build (or a
            // functional test) be measured without any subprojects.
            t.resDirs.from(ext.resDirs)
            t.sourceDirs.from(ext.sourceDirs)
            t.siteDirs.from(root.layout.buildDirectory.dir("stringfit/sites"))
            t.triageFile.set(ext.triageFile)
            t.failOnCutOff.set(ext.failOnCutOff)
            t.outputDir.set(root.layout.buildDirectory.dir("reports/stringfit"))
        }
    }

    /** Every Android module's R class, so cross-module strings can be named. */
    private fun allRClasses(module: Project): List<String> {
        val all =
            module.rootProject.subprojects
                .filter { sub -> ANDROID_PLUGINS.any(sub.plugins::hasPlugin) }
                .mapNotNull { sub ->
                    runCatching { sub.stringFit().rClass.get() }.getOrNull()
                }
        val own = runCatching { module.stringFit().rClass.get() }.getOrNull()
        return (listOfNotNull(own) + all).distinct()
    }

    private fun describeModule(module: Project): ModuleSpec = ModuleSpec(
        path = module.path,
        packageTree = module.stringFit().packageTree.get(),
        rClasses = allRClasses(module),
        harnessFile = module.file(Harness.RELATIVE_PATH),
        sourceDirs = module.stringFit().sourceDirs.files.toList(),
    )

    private companion object {
        const val GROUP = "stringfit"
        const val INSTALL = "stringFitInstallHarness"
        const val REPORT = "stringFitReport"
        const val BASELINE = "stringFitBaseline"
        const val COMPOSE_PLUGIN = "org.jetbrains.kotlin.plugin.compose"
        val ANDROID_PLUGINS = listOf("com.android.application", "com.android.library")

        fun Project.stringFit(): StringFitExtension =
            extensions.getByType(StringFitExtension::class.java)
    }
}
