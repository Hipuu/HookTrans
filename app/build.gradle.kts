plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.hooktrans"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.hooktrans"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        aidl = true
        viewBinding = true
        buildConfig = true
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
            // The Xposed entry classes and everything reached only via reflection must
            // survive. Shrinking is disabled outright: a hook module that gets its entry
            // point renamed simply never loads.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
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

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module")
        jniLibs.useLegacyPackaging = false
    }

    /*
     * ML Kit's offline translation ships a ~17 MB native library per ABI, which is most of the
     * APK. Splitting by ABI means a phone downloads only its own copy: the arm64 build is
     * roughly a third of the universal one. A universal APK is still produced for sideloading
     * onto an unknown device, which is the common case for an Xposed module.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Xposed API: compileOnly on purpose. It is provided by LSPosed at runtime and must
    // never be packaged into the APK.
    compileOnly("de.robv.android.xposed:api:82")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // On-device translation. Runs only in the module's own :engine process, never inside a
    // hooked app, so none of this weight is injected into other apps.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Text recognition for the image-translation hook, also :engine only.
    //
    // The bundled artifacts are used rather than the `play-services-mlkit-*` ones, which are
    // smaller but delegate to Google Play Services. A meaningful share of the LSPosed user base
    // runs de-Googled ROMs where those simply never initialise, so the models are carried in
    // the APK instead. ML Kit has no single recognizer that reads every script, so each one is
    // a separate dependency and OcrEngine picks between them.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
}
