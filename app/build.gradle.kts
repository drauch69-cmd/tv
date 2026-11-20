plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build channel: "main" (default), "test" or "sports". Test and sports install next to the normal app
// (own applicationId and label). Set with -Pchannel=test or the STREAMLINK_CHANNEL env var.
val channel = (findProperty("channel") as String?) ?: System.getenv("STREAMLINK_CHANNEL") ?: "main"
val isTestChannel = channel == "test"
val isSportsChannel = channel == "sports"

android {
    namespace = "com.streamlink.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = when {
            isTestChannel -> "com.streamlink.tv.test"
            isSportsChannel -> "com.streamlink.tv.sports"
            else -> "com.streamlink.tv"
        }
        manifestPlaceholders["appLabel"] = when {
            isTestChannel -> "StreamLink Test"
            isSportsChannel -> "StreamLink Sports"
            else -> "StreamLink"
        }
        minSdk = 26
        targetSdk = 34
        // Each GitHub build gets a higher version number so it installs over the last one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // Which release channel this app updates itself from ("main", "test" or "sports"); see Updater.kt.
        val ch = if (isTestChannel || isSportsChannel) channel else "main"
        buildConfigField("String", "CHANNEL", "\"$ch\"")
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
        // One-person English app: on-screen text is never translated, so these checks are noise.
        disable += setOf("SetTextI18n", "HardcodedText")
        xmlReport = true
        htmlReport = true
        textReport = true
        textOutput = file("build/reports/lint-results.txt")
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
    implementation("com.google.android.gms:play-services-cronet:18.1.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.webkit:webkit:1.18.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (the Android stubs are not usable there).
    testImplementation("org.json:json:20260814")
}
