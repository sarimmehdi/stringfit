plugins {
    id("com.android.library") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    // Applied ONCE here; every Android module is configured automatically.
    id("io.github.sarimmehdi.stringfit")
}

stringFit {
    // Match the Compose version the modules build against.
    composeBom = "androidx.compose:compose-bom:2026.06.01"
    // locales defaults to whatever the project ships (values-XX dirs).
}
