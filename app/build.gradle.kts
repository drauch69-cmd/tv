plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build channel: "main" (default) or "test". The test channel installs next to the normal app
// (own applicationId and label). Set with -Pchannel=test or the STREAMLINK_CHANNEL env var.
val channel = (findProperty("channel") as String?) ?: System.getenv("STREAMLINK_CHANNEL") ?: "main"
val isTestChannel = channel == "test"

android {
    namespace = "com.streamlink.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = if (isTestChannel) "com.streamlink.tv.test" else "com.streamlink.tv"
        manifestPlaceholders["appLabel"] = if (isTestChannel) "StreamLink Test" else "StreamLink"
        minSdk = 26
        targetSdk = 34
        // Each GitHub build gets a higher version number so it installs over the last one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // Which release channel this app updates itself from ("main" or "test"); see Updater.kt.
        buildConfigField("String", "CHANNEL", "\"${if (isTestChannel) "test" else "main"}\"")
    }

    // A fixed signing key, so every new build can be installed over the old one.
    signingConfigs {
        create("release") {
            storeFile = file("signing.keystore")
            storePassword = "streamlink"
            keyAlias = "streamlink"
            keyPassword = "streamlink"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint.txt")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-cronet:$media3")
    implementation("com.google.android.gms:play-services-cronet:18.0.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (the Android stubs are not usable there).
    testImplementation("org.json:json:20240303")
}
