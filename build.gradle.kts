plugins {
    id("com.android.application") version "8.10.1" apply false
    // Must be >= the kotlin-stdlib that media3/okhttp drag in (2.2.10), or the
    // compiler rejects their metadata as "incompatible version of Kotlin".
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // Since Kotlin 2.0 the Compose compiler ships with Kotlin, so this version is not
    // a free choice: it must equal the Kotlin version above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
