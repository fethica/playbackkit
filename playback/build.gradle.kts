plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.fethica.playbackkit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    kotlin {
        // Consumed as a library: every public declaration states its visibility and its
        // return type, so a slip cannot widen the surface by accident.
        explicitApi()
    }
}

dependencies {
    // `api`, not `implementation`: the public surface hands consumers media3 types
    // (Player, Player.Listener) and a CoroutineDispatcher, which a consumer that only
    // declares this library must be able to name at the same versions.
    api(libs.androidx.media3.common)
    api(libs.kotlinx.coroutines.android)
    // Internal only: the HTTP status of a failed load is read from its exception type.
    implementation(libs.androidx.media3.datasource)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

group = property("GROUP") as String
// On JitPack (JITPACK=true) the version must follow the tag being built (VERSION), so an rc tag does not publish a POM versioned with the release VERSION_NAME.
version = if (System.getenv("JITPACK") == "true" && !System.getenv("VERSION").isNullOrBlank()) {
    System.getenv("VERSION")
} else {
    property("VERSION_NAME") as String
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "playbackkit"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("playbackkit")
                description.set("Playback recovery for Media3 players: retry ladders, watchdogs and suppression-aware waiting")
                licenses { license { name.set("MIT") } }
            }
        }
    }
}
