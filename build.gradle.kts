plugins {
    id("com.android.library") version "9.1.0" apply false
    // The example app; not part of the published library.
    id("com.android.application") version "9.1.0" apply false
    // AGP 9 compiles Kotlin itself ("built-in Kotlin") and refuses this
    // plugin in a module. Declaring it here without applying it puts
    // Kotlin Gradle plugin 2.4.0 on the build classpath, which the
    // built-in support then uses instead of the version AGP ships with.
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}
