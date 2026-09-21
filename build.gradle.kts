plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 has Kotlin support built in, so there is no kotlin.android plugin here --
    // applying one is a hard error. This version is what picks the Kotlin toolchain.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
