plugins {
    alias(libs.plugins.android.application)
}

val mapFrameHost =
    providers.gradleProperty("mapFrameHost").orElse("192.168.1.51")
val mapFramePort =
    providers.gradleProperty("mapFramePort").orElse("6201")

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

        // Independent Android navigation-map frame stream. This is not HNCL.
        buildConfigField(
            "String",
            "MAP_FRAME_HOST",
            "\"${mapFrameHost.get()}\"",
        )
        buildConfigField("int", "MAP_FRAME_PORT", mapFramePort.get())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The only target that runs these apps is the NXP i.MX8QM Android guest,
        // which reports arm64-v8a as its ONLY supported ABI (ro.product.cpu.abilist).
        // MapLibre ships libmaplibre.so for four ABIs, so without this filter every
        // build carries ~34 MB of native code the board physically cannot execute --
        // and it is not free at rest: AGP defaults to extractNativeLibs=false, so the
        // whole APK sits in /data/app with all four copies. /data is 1.7 GB total.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        // Generates binding classes for XML layouts.
        viewBinding = true
        buildConfig = true
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
