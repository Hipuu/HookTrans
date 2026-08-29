plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * local.properties is not part of Gradle's project property set (only gradle.properties is),
 * so it has to be read explicitly. Precedence: -P/gradle.properties, then local.properties,
 * then the environment (what CI uses).
 */
val signingProps = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingProp(name: String): String =
    (project.findProperty(name) as? String)
        ?: signingProps.getProperty(name)
        ?: System.getenv(name)
        ?: ""

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
            storePassword = signingProp("HOOKTRANS_STORE_PASSWORD")
            keyAlias = signingProp("HOOKTRANS_KEY_ALIAS")
            keyPassword = signingProp("HOOKTRANS_KEY_PASSWORD")
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
