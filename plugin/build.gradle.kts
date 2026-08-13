plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.1.1"
    kotlin("jvm") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "io.github.sarimmehdi"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Plugins are loaded by the Gradle daemon's own Kotlin runtime, so the
        // emitted metadata has to be readable by the OLDEST Gradle we support,
        // not the newest one we happen to build with.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.7.0")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // CI widens the TestKit matrix with -Pstringfit.gradleVersions=8.13,9.6.1
    val versions = providers.gradleProperty("stringfit.gradleVersions")
    inputs.property("gradleVersions", versions).optional(true)
    doFirst { versions.orNull?.let { systemProperties["stringfit.gradleVersions"] = it } }
}

// Oldest Gradle the plugin is verified against; see FunctionalTest.MINIMUM_GRADLE.
val minimumGradle = "8.8"

gradlePlugin {
    website = "https://github.com/sarimmehdi/stringfit"
    vcsUrl = "https://github.com/sarimmehdi/stringfit.git"
    plugins {
        create("stringfit") {
            id = "io.github.sarimmehdi.stringfit"
            implementationClass = "dev.stringfit.StringFitPlugin"
            displayName = "StringFit"
            description = "Measures how much room each translatable string actually has, " +
                "by rendering your Compose @Previews and reporting per-site width and line budgets."
            tags = listOf("android", "compose", "localization", "i18n", "translation")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
    parallel = true
}
