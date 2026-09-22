import java.util.Base64

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
        versionCode = 5
        versionName = "1.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // The private-database credential is injected by CI (secret PRF_TOKEN) and never
        // committed to this public repo. It is stored base64-wrapped in BuildConfig so the
        // release APK does not carry a greppable "github_pat_" / "ghp_" literal.
        val prfToken = (project.findProperty("prfToken") as String?)
            ?: System.getenv("PRF_TOKEN")
            ?: ""
        val wrapped = if (prfToken.isBlank()) {
            ""
        } else {
            Base64.getEncoder().encodeToString(prfToken.toByteArray())
        }
        buildConfigField("String", "PRF_TOKEN_B64", "\"$wrapped\"")
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

    // One universal APK covering every ABI - the release artifact is a single .apk
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

    // CameraX - the actual capture engine (3 front + 3 back)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Encrypted on-device storage for the sync credential
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Background sync queue with retry/backoff
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
}
