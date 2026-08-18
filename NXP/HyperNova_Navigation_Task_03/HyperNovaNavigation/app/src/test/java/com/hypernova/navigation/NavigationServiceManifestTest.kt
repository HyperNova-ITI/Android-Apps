package com.hypernova.navigation

import com.hypernova.contracts.navigation.NavigationContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavigationServiceManifestTest {
    @Test
    fun commandService_usesVehicleGatewaySignaturePermissionWithoutRedeclaringIt() {
        val manifest =
            projectFile("src/main/AndroidManifest.xml")
                .readText()

        assertTrue(
            manifest.contains(
                "android:name=\".service." +
                    "NavigationCommandService\""
            )
        )
        assertTrue(
            manifest.contains(
                "android:permission=\"" +
                    "com.hypernova.permission.ACCESS_VEHICLE_GATEWAY" +
                    "\""
            )
        )
        assertTrue(
            manifest.contains(
                "android:name=\"" +
                    NavigationContract.BIND_COMMAND_ACTION +
                    "\""
            )
        )
        assertFalse(manifest.contains("<permission"))
        assertFalse(
            manifest.contains(
                "android.intent.category.HOME"
            )
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates =
            listOf(
                File(relativePath),
                File("app/$relativePath"),
                File(
                    "HyperNovaNavigation/app/$relativePath"
                )
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not resolve $relativePath")
    }
}
