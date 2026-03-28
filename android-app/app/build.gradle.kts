plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.geminivision"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geminivision"
        minSdk = 31 // DAT SDK requiere API 31+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Backend URL configurable por build variant
        buildConfigField("String", "BACKEND_URL", "\"ws://10.0.2.2:3000\"") // emulator -> host
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "BACKEND_URL", "\"wss://your-production-url.railway.app\"")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val mwdatVersion = "0.5.0"

dependencies {
    // Meta Wearables DAT SDK
    implementation("com.meta.wearable:mwdat-core:$mwdatVersion")
    implementation("com.meta.wearable:mwdat-camera:$mwdatVersion")
    debugImplementation("com.meta.wearable:mwdat-mockdevice:$mwdatVersion")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // JSON
    implementation("org.json:json:20240303")
}
