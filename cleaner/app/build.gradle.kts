plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvcleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvcleaner"
        minSdk = 28
        targetSdk = 34
        // Each GitHub build gets a higher version number so it installs over the last one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Same fixed key as StreamLink, so every new build installs over the old one.
    signingConfigs {
        create("release") {
            storeFile = file("../../app/signing.keystore")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
