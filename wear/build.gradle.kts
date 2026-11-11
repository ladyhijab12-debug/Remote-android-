plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.remote.swim"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.remote.swim"
        minSdk = 30
        // 34 keeps heart rate behind BODY_SENSORS on every Wear OS version the watch can run.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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
}

dependencies {
    // Swim tracking (lengths, distance, strokes, heart rate) from the watch's own sensors.
    implementation("androidx.health:health-services-client:1.0.0")
    // Health Services returns Guava ListenableFutures.
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
}
