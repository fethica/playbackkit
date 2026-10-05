buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 9 compiles Kotlin itself and uses the Kotlin Gradle plugin found on the build
        // classpath, so this line is what pins the compiler to the catalog's Kotlin version.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
