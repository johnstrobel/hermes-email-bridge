plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace   = "com.hermes.mediacontrol"
    compileSdk  = 34

    defaultConfig {
        applicationId = "com.hermes.mediacontrol"
        minSdk        = 29   // Android 10 — MediaSession API stable, background restrictions known
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Embedded HTTP server — no Netty/OkHttp overhead, ~50 KB jar
    implementation("fi.iki.elonen:nanohttpd:2.3.1")

    // JSON (org.json is bundled in Android, but explicit dep ensures IDE resolution)
    // No additional dep needed — android.jar provides org.json
}
