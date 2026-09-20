plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "xyz.utkin.kino"
    // media3 1.11.1 requires its consumers to compile against 36 or later.
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.utkin.kino"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // android.util.Log is a stub in JVM unit tests and throws "not mocked" by default.
    // parseMultistatus does not log today, but this keeps a future Log call from
    // breaking the tests in a confusing way.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Only reason OkHttp is here: HttpURLConnection.setRequestMethod("PROPFIND")
    // throws ProtocolException, so the platform client cannot speak WebDAV.
    // Playback uses media3's own DefaultHttpDataSource.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
