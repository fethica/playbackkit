plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The short HEAD hash at build time, shown in the developer panel. `--dirty` marks a build
// made from uncommitted changes. Falls back to "unknown" outside a git checkout.
val gitCommit: String = runCatching {
    providers.exec {
        commandLine("git", "describe", "--always", "--dirty", "--abbrev=7")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull().orEmpty().ifEmpty { "unknown" }

android {
    // An application id of its own: installing the sample replaces nothing else.
    namespace = "com.fethica.playbackkit.sample"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "com.fethica.playbackkit.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-sample"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":playback"))
    // The sample owns the player and the media session; the library ships neither.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    // The fixture generator and the loopback server are plain JVM code, tested on the JVM.
    testImplementation(libs.junit)
}
