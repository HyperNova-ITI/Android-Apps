pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HyperNovaGoogleNavigation"

include(":app")

include(":hypernova-contracts")
project(":hypernova-contracts").projectDir =
    file("../../HyperNova_Contracts/contracts")

include(":nova-visuals")
project(":nova-visuals").projectDir =
    file("../../HyperNova_Nova_Visuals")

