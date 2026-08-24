import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val nxpDeployment =
    providers.gradleProperty("nxpDeployment")
        .map(String::toBoolean)
        .orElse(false)

val mapsSecrets = Properties()
val mapsSecretsFile =
    listOf(
        rootProject.file("secrets.properties"),
        rootProject.file("../HyperNova_Google_Navigation_Task_11/HyperNovaGoogleNavigation/secrets.properties"),
    ).firstOrNull { it.isFile }
mapsSecretsFile?.inputStream()?.use(mapsSecrets::load)
val launcherMapsApiKey = mapsSecrets.getProperty("MAPS_API_KEY").orEmpty()
val escapedLauncherMapsApiKey =
    launcherMapsApiKey.replace("\\", "\\\\").replace("\"", "\\\"")

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
        buildConfigField("String", "MAPS_API_KEY", "\"$escapedLauncherMapsApiKey\"")

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

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
            if (!nxpDeployment.get()) {
                // Install the development app beside the system launcher.
                applicationIdSuffix = ".dev"
                versionNameSuffix = "-dev"
            }
        }

        create("demo") {
            // Replace the production launcher over ADB while retaining debug signing
            // and diagnostics. This avoids mutating the build file in deployment scripts.
            initWith(getByName("debug"))
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
        buildConfig = true
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
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
