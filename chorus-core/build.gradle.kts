/*
 * @context
 * refs: ARCHITECTURE.md, sections 2.2, 3.9, and 6
 * related: gradle/libs.versions.toml; AGENTS.md;
 *          chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/
 * sync: Keep schema source annotations out of packaged resources, and local integration tests in check.
 * @endcontext
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.FilterReader
import java.io.Reader
import java.io.StringReader

buildscript {
    dependencies { classpath(localGroovy()) }
}

class MinifiedSchemaReader(reader: Reader) :
    FilterReader(
        StringReader(
            reader.use { JsonOutput.toJson(withoutComments(JsonSlurper().parse(it))) },
        ),
    ) {
    companion object {
        private fun withoutComments(value: Any?): Any? =
            when (value) {
                is Map<*, *> ->
                    value.filterKeys { it != "\$comment" }.mapValues { withoutComments(it.value) }
                is List<*> -> value.map { withoutComments(it) }
                else -> value
            }
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("me/pectics/chorus/core/config/schema/**/*.schema.json") {
        filter<MinifiedSchemaReader>()
    }
}

val integrationTest =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += output + compileClasspath
    }

configurations[integrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)

configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

dependencies {
    implementation(libs.snakeyaml)
    implementation(libs.json.schema.validator) {
        // Keep YAML parsing under the loader's explicit resource and type limits.
        exclude(group = "tools.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    implementation(libs.jackson.databind)
    implementation(libs.nimbus.jose.jwt)
    runtimeOnly(libs.sqlite.jdbc)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val integrationTestTask =
    tasks.register<Test>("integrationTest") {
        description = "Runs local SQLite and configured engine integration tests."
        group = "verification"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        systemProperty("chorus.integration.classpath", integrationTest.runtimeClasspath.asPath)
        shouldRunAfter(tasks.test)
    }

tasks.check { dependsOn(integrationTestTask) }
