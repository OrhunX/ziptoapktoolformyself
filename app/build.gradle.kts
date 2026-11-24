import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sen0413.ultimatewidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sen0413.ultimatewidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "2.2"
        // Redirect URI registered in the Spotify developer dashboard: ultimatewidget://spotify-callback
        manifestPlaceholders["redirectSchemeName"] = "ultimatewidget"
        manifestPlaceholders["redirectHostName"] = "spotify-callback"
    }

    // One fixed debug key (Android's standard debug credentials) so every CI build installs as an
    // update over the previous one instead of being rejected for a different signature.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.palette)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core) // QR codes for playlist share codes (encoding only)
    // Spotify Android SDK (Apache 2.0), from github.com/spotify/android-sdk: App Remote plays exact
    // songs in the background; Auth gets the one-time approval. Gson and Custom Tabs are their deps.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(files("libs/spotify-auth-release-2.1.0.aar"))
    implementation(libs.gson)
    implementation(libs.androidx.browser)

    testImplementation(libs.junit)
}
