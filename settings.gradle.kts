/*
 * @context
 * related: .gitignore; README.md
 * sync: Keep enabled modules, the Git whitelist, and build instructions aligned.
 * @endcontext
 */

import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Settings plugins cannot access the version catalog.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "chorus"

include("chorus-core", "chorus-common")
