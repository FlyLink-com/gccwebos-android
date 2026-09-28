plugins {
    id("com.android.application") version "9.2.0" apply false
    // AGP 9.2's built-in Kotlin compiler is 2.3.10; do not add kotlin-android again.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
