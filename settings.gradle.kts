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

rootProject.name = "gccwebos-android"
include(":app")

// Explicit, permission-free copy-only QA candidate; never part of ordinary builds.
if (providers.gradleProperty("copyBoundaryLab").orNull == "true") {
    include(":copyBoundaryLab")
}
