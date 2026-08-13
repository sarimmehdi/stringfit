plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "io.github.sarimmehdi"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.7.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

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
