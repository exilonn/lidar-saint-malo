import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Read the TideCheck API key from local.properties (gitignored). Never hardcode the key.
// Exposed to code via BuildConfig.TIDECHECK_API_KEY.
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val tideCheckApiKey: String = localProps.getProperty("TIDECHECK_API_KEY", "")
// MapTiler basemap key (gitignored, like the TideCheck key). Add MAPTILER_KEY=... to
// local.properties; the map's basemap tiles won't load until it's set. Never hardcode it.
val mapTilerKey: String = localProps.getProperty("MAPTILER_KEY", "")

android {
    namespace = "com.exilon.tides"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.exilon.tides"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "TIDECHECK_API_KEY", "\"$tideCheckApiKey\"")
        buildConfigField("String", "TIDECHECK_BASE_URL", "\"https://tidecheck.com/api/\"")
        buildConfigField("String", "MAPTILER_KEY", "\"$mapTilerKey\"")

        // We share APKs directly and only target arm64 physical devices, so package just the
        // arm64-v8a MapLibre native libs to keep the APK small. NOTE: x86/x86_64 emulators won't
        // have the native lib and the map screen will fail there — use an arm64 device/emulator.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM-aligned versions)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Room (single source of truth)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines (+ Play Services await() bridge for FusedLocation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Glance home-screen widget
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Background refresh
    implementation(libs.androidx.work.runtime.ktx)

    // Location
    implementation(libs.play.services.location)

    // Interactive tide map (app-only; never used in the widget path)
    implementation(libs.maplibre.android)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
