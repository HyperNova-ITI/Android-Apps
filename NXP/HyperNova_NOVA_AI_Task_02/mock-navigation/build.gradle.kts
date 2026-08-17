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
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-mock"
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":hypernova-contracts"))
}
