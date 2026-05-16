include(":app", ":contract", ":headunitlauncher-helper-app")

project(":headunitlauncher-helper-app").projectDir = file("headunitlauncher-helper/app")


rootProject.name = "headunit"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
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
