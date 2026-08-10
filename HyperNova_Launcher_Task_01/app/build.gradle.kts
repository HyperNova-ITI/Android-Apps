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

    // Compile against the frozen sibling contracts without building or writing
    // inside the read-only HyperNova_Contracts repository.
    sourceSets.getByName("main") {
        java.directories.add(
            rootProject.file("../HyperNova_Contracts/contracts/src/main/java").absolutePath
        )
        aidl.directories.add(
            rootProject.file("../HyperNova_Contracts/contracts/src/main/aidl").absolutePath
        )
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
        aidl = true
        // Generate binding classes for XML layout files.
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":nova-visuals"))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // Read-only map renderer; version matches HyperNova Navigation.
    implementation(libs.maplibre.android.sdk)

    // Connect to the future HyperNova MediaSessionService.
    implementation("androidx.media3:media3-session:1.10.1")

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
