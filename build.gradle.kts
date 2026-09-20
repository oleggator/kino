plugins {
    id("com.android.application") version "8.10.1" apply false
    // Must be >= the kotlin-stdlib that media3/okhttp drag in (2.2.10), or the
    // compiler rejects their metadata as "incompatible version of Kotlin".
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
