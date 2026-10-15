/*
 * @context
 * related: gradle/libs.versions.toml; settings.gradle.kts
 * sync: Pin formatter versions in the catalog and limit targets to enabled modules.
 * @endcontext
 */

import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    java
    alias(libs.plugins.spotless)
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "chorus-core/*.gradle.kts", "chorus-common/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    format("misc") {
        target(".gitignore", "gradle.properties", "gradle/libs.versions.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
    }

    configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat(rootProject.libs.versions.google.java.format.get()).aosp()
            endWithNewline()
        }
    }
}
