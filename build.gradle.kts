/*
 * @context
 * related: gradle/libs.versions.toml; settings.gradle.kts; gradle/spotbugs-exclude.xml
 * sync: Pin formatter and analyzer versions in the catalog and limit checks to enabled modules.
 * @endcontext
 */

import com.diffplug.gradle.spotless.SpotlessExtension
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    java
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs) apply false
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "chorus-core/*.gradle.kts", "chorus-common/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    format("misc") {
        target(
            ".gitignore",
            "gradle.properties",
            "gradle/libs.versions.toml",
            "gradle/spotbugs-exclude.xml",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "com.github.spotbugs")

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

    configure<SpotBugsExtension> {
        toolVersion.set(rootProject.libs.versions.spotbugs.engine)
        effort.set(Effort.MAX)
        reportLevel.set(Confidence.MEDIUM)
        ignoreFailures.set(false)
        excludeFilter.set(rootProject.layout.projectDirectory.file("gradle/spotbugs-exclude.xml"))
    }

    tasks.withType<SpotBugsTask>().configureEach {
        reports.create("html") { required.set(true) }
        reports.create("xml") { required.set(true) }
    }
}

tasks.register("spotbugs") {
    description = "Runs SpotBugs for all source sets in enabled modules without executing tests."
    group = "verification"
    dependsOn(subprojects.map { it.tasks.withType<SpotBugsTask>() })
}
