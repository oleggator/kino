import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xyz.utkin.kino"
    // media3 1.11.1 requires its consumers to compile against 36 or later.
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.utkin.kino"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            // Compose ships its own consumer rules, so there is nothing to write in
            // proguard-rules.pro. This is what pays back the size Compose adds.
            isMinifyEnabled = true
            isShrinkResources = true
            // ponytail: signed with the debug key so `assembleRelease` produces
            // something installable. This app is sideloaded, never published. Swap in
            // a real keystore if that ever changes.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // android.util.Log is a stub in JVM unit tests and throws "not mocked" by default.
    // parseMultistatus does not log today, but this keeps a future Log call from
    // breaking the tests in a confusing way.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Top level, not inside `android {}`: kotlinOptions is deprecated in Kotlin 2.x in
// favour of the compilerOptions DSL on the Kotlin extension.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Not the newest BOM on purpose: from compose 1.11 on, the artifacts demand
    // compileSdk 37 and AGP 9.1. This one pins compose 1.10.5 / material3 1.4.0, all
    // minCompileSdk=35, and 1.10.5 already satisfies what tv-material asks for.
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.tv:tv-material:1.1.0")
    // tv-material ships no text field. Google's own TV guidance is to borrow
    // material3's for the few components TV Material does not have.
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Only reason OkHttp is here: HttpURLConnection.setRequestMethod("PROPFIND")
    // throws ProtocolException, so the platform client cannot speak WebDAV.
    // Playback uses media3's own DefaultHttpDataSource.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
