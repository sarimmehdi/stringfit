pluginManagement {
    includeBuild("..")                       // use the plugin from this repo
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "stringfit-sample"
include(":app")
include(":core-ui")
