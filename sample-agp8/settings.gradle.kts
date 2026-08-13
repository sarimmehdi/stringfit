pluginManagement {
    includeBuild("..")
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "stringfit-sample-agp8"
include(":app")
