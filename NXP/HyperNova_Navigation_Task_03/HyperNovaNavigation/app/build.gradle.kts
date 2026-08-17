plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hypernova.navigation"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.navigation"

        // Shared HyperNova Demo API v1 baseline.
        minSdk = 35

        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // Generates binding classes for XML layouts.
        viewBinding = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Frozen cross-APK AIDL source of truth.
    implementation(project(":hypernova-contracts"))
    implementation(project(":nova-visuals"))

    // AndroidX
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    // Material UI
    implementation(libs.material)

    // Real interactive map renderer
    implementation(libs.maplibre.android.sdk)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
