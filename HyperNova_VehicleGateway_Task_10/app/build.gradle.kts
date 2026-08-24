plugins {
    id("com.android.application")
}

// The QNX guest holds both 192.168.1.51 (SOME/IP segment) and 192.168.0.51 (TC397/Android
// segment) on vtnet0, and the Android guest has exactly one address and one route:
// 192.168.0.100/24 via eth0, with no default gateway. So 192.168.0.51 is the only address on
// the QNX guest that Android can actually reach, for both the gateway and the cluster link.
//
// This previously defaulted to 10.0.2.2 -- the Android *emulator's* alias for its host machine,
// which routes nowhere on real hardware. Every build that did not pass -PgatewayHost silently
// dialled an unreachable address and simply never connected, with no error at build time.
val relayHost = providers.gradleProperty("gatewayHost").orElse("192.168.0.51")
val relayPort = providers.gradleProperty("gatewayPort").orElse("6100")

val clusterHost = providers.gradleProperty("clusterHost").orElse(relayHost)
val clusterPort = providers.gradleProperty("clusterPort").orElse("6200")

val mediaClusterHost = providers.gradleProperty("mediaClusterHost").orElse(clusterHost)
val mediaClusterPort = providers.gradleProperty("mediaClusterPort").orElse("6300")

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

        // Existing Android -> QNX vehicle/climate gateway.
        buildConfigField("String", "GATEWAY_HOST", "\"${relayHost.get()}\"")
        buildConfigField("int", "GATEWAY_PORT", relayPort.get())

        // Dedicated Android -> QNX Digital Cluster navigation channel.
        // Kept separate from HNVG/6100 so cluster failures cannot disturb
        // the existing vehicle/climate gateway session.
        buildConfigField("String", "CLUSTER_HOST", "\"${clusterHost.get()}\"")
        buildConfigField("int", "CLUSTER_PORT", clusterPort.get())

        // Dedicated Android -> QNX Digital Cluster media channel.
        // Kept separate from HNVG/6100 and HNCL/6200 so a media failure
        // cannot disturb the other cluster/vehicle sessions.
        buildConfigField("String", "MEDIA_CLUSTER_HOST", "\"${mediaClusterHost.get()}\"")
        buildConfigField("int", "MEDIA_CLUSTER_PORT", mediaClusterPort.get())
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
