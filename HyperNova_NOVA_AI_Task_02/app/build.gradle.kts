plugins {
    alias(libs.plugins.android.application)
}

val novaHost = providers.gradleProperty("novaHost").orElse("192.168.10.20")
val novaAssistantVolume = providers.gradleProperty("novaAssistantVolume").orElse("-1")

android {
    namespace = "com.hypernova.ai"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.ai"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "NOVA_DEFAULT_HOST", "\"${novaHost.get()}\"")
        buildConfigField("int", "NOVA_ASSISTANT_VOLUME_INDEX", novaAssistantVolume.get())
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":hypernova-contracts"))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)
}
