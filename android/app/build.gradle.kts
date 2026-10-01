import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningFile = rootProject.file("release-signing.properties")
val releaseSigningRequested = gradle.startParameter.taskNames.any {
    it.contains("Compact", ignoreCase = true) || it.contains("Release", ignoreCase = true) ||
        it.substringAfterLast(':').lowercase() in setOf("assemble", "build", "bundle", "publish")
}
val releaseSigning = Properties().apply {
    if (releaseSigningRequested && releaseSigningFile.exists()) releaseSigningFile.inputStream().use { load(it) }
}
if (releaseSigningRequested && !releaseSigningFile.exists()) {
    error("Release signing configuration is missing: android/release-signing.properties")
}

android {
    namespace = "com.windowslockpin.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.windowslockpin.companion"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("publishing") {
            if (releaseSigningRequested && releaseSigningFile.exists()) {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("publishing")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // Distribution build: official application ID and a persistent release certificate.
        create("compact") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)

    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.material)

    // Standalone embedded ZXing QR code scanner - no Google Play Services dependency (Xiaomi / AOSP compatible)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
}
