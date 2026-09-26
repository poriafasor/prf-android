plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.prf.security"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prf.security"
        // API 23 = Android 6. Covers the deprecated/low-end devices the field still runs.
        minSdk = 23
        targetSdk = 34
        versionCode = 13
        versionName = "1.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // The release APK is signed with the standard debug keystore that CI regenerates,
    // so the build is reproducible and the artifact always installs. Replace with a
    // real upload keystore before going to production.
    signingConfigs {
        create("release") {
            storeFile = File(rootProject.projectDir, "debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // ── UNIVERSAL APK ──────────────────────────────────────────────────────
    // Exactly one app-release.apk that installs on every architecture. The bundle
    // block below only governs the .aab path; assembleRelease is made universal by
    // disabling ABI splits explicitly here so a future config change can never
    // quietly start emitting per-architecture APKs that fail on other devices.
    splits {
        abi {
            isEnable = false
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    bundle {
        language { enableSplit = false }
        density  { enableSplit = false }
        abi      { enableSplit = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")


    // Encrypted at-rest storage for the device key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Background sync queue with retry/backoff
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Plus Codes (Open Location Code) for the third location format
    implementation("com.google.openlocationcode:openlocationcode:1.0.0")

    // ── CameraX ────────────────────────────────────────────────────────────
    // The attendance capture is automatic: three shots from the front lens and
    // three from the back, taken back to back after one consent tap. The system
    // camera intent (ACTION_IMAGE_CAPTURE) cannot do that — it hands control to
    // another app and needs a shutter press per photo — so this app drives the
    // camera itself through CameraX, with the user watching a live preview and a
    // step list the whole time.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
}
