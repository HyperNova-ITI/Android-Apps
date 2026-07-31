package com.hypernova.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductResourceContractTest {

    @Test
    fun homeShortcutAndCategoryResourcesMatchFinalProductSet() {
        val home = projectFile("src/main/res/layout/panel_home.xml")
            .readText()
        val search = projectFile("src/main/res/layout/panel_search.xml")
            .readText()
        assertTrue(home.contains("@+id/fuelButton"))
        assertTrue(home.contains("@drawable/ic_fuel"))
        assertTrue(search.contains("@+id/fuelButton"))
    }

    @Test
    fun userFacingResourcesContainNoProhibitedStartWording() {
        val resourceRoot = projectFile("src/main/res")
        val prohibited =
            Regex(
                "fixed[\\s_-]*origin",
                RegexOption.IGNORE_CASE
            )
        val matches =
            resourceRoot.walkTopDown()
                .filter { it.isFile }
                .filter {
                    it.extension in setOf("xml", "txt")
                }
                .filter {
                    prohibited.containsMatchIn(it.readText())
                }
                .toList()

        assertTrue(
            "Prohibited wording in: $matches",
            matches.isEmpty()
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates =
            listOf(
                File(relativePath),
                File("app/$relativePath"),
                File("HyperNovaNavigation/app/$relativePath")
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not resolve $relativePath")
    }
}
