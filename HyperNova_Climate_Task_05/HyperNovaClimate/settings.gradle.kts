pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HyperNovaClimate"
include(":app")

// Shared, frozen IPC contract module (single source of truth — never fork the AIDL).
// Resolves to the repository's one HyperNova_Contracts/contracts directory.
include(":hypernova-contracts")
project(":hypernova-contracts").projectDir =
    file("../../HyperNova_Contracts/contracts")
