plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.sarimmehdi.stringfit")
}

android {
    namespace = "dev.stringfit.sample"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.logging", "stdout") }
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(bom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.sergio-sastre.ComposablePreviewScanner:android:0.9.2")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
    testLogging { showStandardStreams = true }
}

stringFit {
    packageTree = "dev.stringfit.sample"
    rClass = "dev.stringfit.sample.R"
}
