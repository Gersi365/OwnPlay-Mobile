buildscript {
    dependencies {
        // AGP 9 uses built-in Kotlin. Pin the Kotlin Gradle Plugin runtime so the
        // Compose compiler plugin and built-in Kotlin use the same Kotlin line.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
