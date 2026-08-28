plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * A target for the module to translate.
 *
 * This is not a demo. Its job is to *verify* the guarantee the module is built around: that
 * translated text changes only what is drawn, never what the app reads back. Every check runs
 * inside the hooked process and reports PASS/FAIL to logcat, so a regression in the hook shows
 * up as a failing assertion rather than as something a human has to spot on screen.
 */
android {
    namespace = "io.hooktrans.testapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.hooktrans.testapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/hooktrans.jks")
            storePassword = project.findProperty("HOOKTRANS_STORE_PASSWORD") as? String ?: ""
            keyAlias = project.findProperty("HOOKTRANS_KEY_ALIAS") as? String ?: ""
            keyPassword = project.findProperty("HOOKTRANS_KEY_PASSWORD") as? String ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
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
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
