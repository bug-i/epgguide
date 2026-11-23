plugins {
    id("com.android.application")
    // Kotlin selbst ist seit AGP 9 eingebaut, das alte kotlin-android-Plugin entfällt
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.epgguide"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "de.epgguide"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
}
