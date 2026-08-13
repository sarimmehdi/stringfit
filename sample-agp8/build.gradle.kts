// Proves the plugin works on the AGP 8 / Gradle 8 line, where Kotlin still needs
// its own plugin and the class output lands somewhere different from AGP 9.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("io.github.sarimmehdi.stringfit")
}
