package com.hypernova.ai.vehicle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VehicleGatewayManifestTest {
    @Test
    fun novaRequestsGatewayPermissionForTelemetryAndFaultCallbacks() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        assertTrue(
            manifest.contains(
                "<uses-permission " +
                    "android:name=\"com.hypernova.permission.ACCESS_VEHICLE_GATEWAY\""
            )
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("app/$relativePath"))
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not resolve $relativePath")
    }
}
