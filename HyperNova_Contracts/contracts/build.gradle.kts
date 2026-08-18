plugins {
    id("com.android.library")
}

android {
    namespace = "com.hypernova.contracts"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 35
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

/*
 * Give every consuming build its own output tree.
 *
 * This module is included by all six cockpit apps as ":nova-visuals" pointing at one directory on
 * disk, so without this they share a single build/ folder. Building one app and then another made
 * the second see the first's outputs already sitting in its incremental task directories, and
 * bundleLibRuntimeToDir failed with "file already exists, it cannot be overwritten". Keying the
 * build directory on the consuming root project keeps the trees apart.
 */
layout.buildDirectory.set(layout.projectDirectory.dir("build/${rootProject.name}"))
