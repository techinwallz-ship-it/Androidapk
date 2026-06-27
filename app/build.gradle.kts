plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.pisignage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.pisignage"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // R8 disabled: dedicated sideloaded signage boxes gain nothing from shrinking/
            // obfuscation, and turning it off makes the release build behave exactly like the
            // thoroughly field-tested debug build (no release-only reflection breakage risk).
            // Keep-rules remain in proguard-rules.pro in case R8 is ever re-enabled.
            isMinifyEnabled = false
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

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation("com.airbnb.android:lottie:6.3.0")
    implementation("androidx.appcompat:appcompat:1.7.0")


    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Socket.IO (ONLY ONCE)
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }
}
