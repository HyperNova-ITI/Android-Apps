plugins {
    id("com.android.application")
}

val relayHost = providers.gradleProperty("gatewayHost").orElse("10.0.2.2")
val relayPort = providers.gradleProperty("gatewayPort").orElse("6100")
val allowPlaintextGateway = providers.gradleProperty("gatewayAllowPlaintext")
    .map { it.toBooleanStrict() }
    .orElse(false)

android {
    namespace = "com.hypernova.vehiclegateway"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hypernova.vehiclegateway"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GATEWAY_HOST", "\"${relayHost.get()}\"")
        buildConfigField("int", "GATEWAY_PORT", relayPort.get())
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "ALLOW_PLAINTEXT_GATEWAY", "true")
        }
        release {
            optimization.enable = false
            buildConfigField(
                "boolean",
                "ALLOW_PLAINTEXT_GATEWAY",
                allowPlaintextGateway.get().toString(),
            )
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":hypernova-contracts"))
    testImplementation("junit:junit:4.13.2")
}
