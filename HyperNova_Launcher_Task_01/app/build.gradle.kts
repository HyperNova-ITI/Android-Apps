plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hypernova.launcher"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.launcher"

        minSdk = 35
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Install the development app beside the system launcher.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }

        release {
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        // Generate binding classes for XML layout files.
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Connect to the future HyperNova MediaSessionService.
    implementation("androidx.media3:media3-session:1.10.1")

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}