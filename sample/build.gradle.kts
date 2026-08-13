plugins {
    id("com.android.library") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    // Applied ONCE here; every Android module is configured automatically.
    id("io.github.sarimmehdi.stringfit")
}

stringFit {
    // Nothing to configure: the Compose BOM is detected from each module, and
    // locales default to whatever the project ships (values-XX directories).
}
