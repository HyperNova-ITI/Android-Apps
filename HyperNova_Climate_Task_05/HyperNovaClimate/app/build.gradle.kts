plugins {
    // AGP 9.0+ compiles Kotlin sources natively; the org.jetbrains.kotlin.android
    // plugin is no longer required (and now conflicts).
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hypernova.climate"

    compileSdk {
        // Android 16 (API 36.1).
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.climate"

        minSdk = 35
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
        }
        create("demo") {
            initWith(getByName("debug"))
            // RPi cockpit demo: debug ClimatePreview behavior, production identity.
            applicationIdSuffix = ""
            versionNameSuffix = "-demo"
            matchingFallbacks += listOf("debug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        aidl = true
        viewBinding = true
    }

    // Keep downloadable icon assets in a dedicated folder, separate from the
    // hand-authored drawables (backgrounds, launcher, vehicle illustration).
    // Both compile into the same resource namespace, so @drawable/ic_* still
    // resolves. Icon files here must NOT duplicate a name in src/main/res.
    sourceSets {
        getByName("main") {
            res.srcDir("src/main/res-icons")
        }
        getByName("demo") {
            kotlin.directories.add("src/debug/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Frozen shared IPC contract (AIDL + Java types). Never fork the AIDL.
    implementation(project(":hypernova-contracts"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
