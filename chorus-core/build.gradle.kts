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
import java.math.BigDecimal

plugins {
    alias(libs.plugins.pitest)
}

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
    inputs.property("schemaResourceFormat", "minified-without-comments")
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

val sqlIntegrationTest =
    sourceSets.create("sqlIntegrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += output + compileClasspath
    }

val redisIntegrationTest =
    sourceSets.create("redisIntegrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += output + compileClasspath
    }

configurations[sqlIntegrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)

configurations[sqlIntegrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

configurations[redisIntegrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)

configurations[redisIntegrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

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
    implementation(libs.zstd.jni)
    runtimeOnly(libs.sqlite.jdbc)
    implementation(libs.hikari)
    implementation(libs.jedis)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mysql.connector)
    runtimeOnly(libs.mariadb.connector)

    add(sqlIntegrationTest.implementationConfigurationName, libs.testcontainers)
    add(redisIntegrationTest.implementationConfigurationName, libs.testcontainers)

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

// SQL service tests are opt-in; check/build remain independent of Docker and external databases.
tasks.register<Test>("sqlIntegrationTest") {
    description =
        "Runs real PostgreSQL, MySQL, and MariaDB server node registry and TLS integration tests."
    group = "verification"
    testClassesDirs = sqlIntegrationTest.output.classesDirs
    classpath = sqlIntegrationTest.runtimeClasspath
    systemProperty(
        "chorus.sql.postgresql.image",
        "postgres:" + libs.versions.postgresql.test.image.get(),
    )
    systemProperty("chorus.sql.mysql.image", "mysql:" + libs.versions.mysql.test.image.get())
    systemProperty("chorus.sql.mariadb.image", "mariadb:" + libs.versions.mariadb.test.image.get())
    shouldRunAfter(integrationTestTask)
}

// Redis service tests are opt-in; check/build remain independent of Docker and external services.
tasks.register<Test>("redisIntegrationTest") {
    description = "Runs real Redis replay, ACL, and TLS integration tests."
    group = "verification"
    testClassesDirs = redisIntegrationTest.output.classesDirs
    classpath = redisIntegrationTest.runtimeClasspath
    systemProperty("chorus.redis.image", "redis:" + libs.versions.redis.test.image.get())
    shouldRunAfter(integrationTestTask)
}

val pitestEngineVersion = libs.versions.pitest.engine.get()
val pitestJunit5PluginVersion = libs.versions.pitest.junit5.plugin.get()

pitest {
    pitestVersion.set(pitestEngineVersion)
    junit5PluginVersion.set(pitestJunit5PluginVersion)
    targetClasses.set(setOf("me.pectics.chorus.core.*"))
    // Live-service adapters need real PostgreSQL/MySQL/MariaDB/Redis services; PIT cannot start a
    // database per mutant, so their behavior is verified by the opt-in integration suites.
    excludedClasses.set(
        setOf(
            "me.pectics.chorus.core.registry.sql.JdbcPool",
            "me.pectics.chorus.core.registry.sql.JdbcSchemaInitializer",
            "me.pectics.chorus.core.registry.sql.JdbcServerNodeStore",
            "me.pectics.chorus.core.replay.registry.JdbcReplayStore",
            "me.pectics.chorus.core.replay.redis.JedisReplayStore",
        ),
    )
    testSourceSets.set(setOf(sourceSets.test.get(), sourceSets.getByName("integrationTest")))
    mutators.set(setOf("STRONGER"))
    outputFormats.set(setOf("HTML", "XML"))
    timestampedReports.set(false)
    threads.set(Runtime.getRuntime().availableProcessors().coerceAtMost(8))
    timeoutConstInMillis.set(10_000)
    timeoutFactor.set(BigDecimal("2.0"))
    fullMutationMatrix.set(true)
    mutationThreshold.set(90)
    coverageThreshold.set(90)
    failWhenNoMutations.set(true)
    verbose.set(false)
    jvmArgs.set(
        listOf(
            "-Dchorus.integration.classpath=" +
                sourceSets.getByName("integrationTest").runtimeClasspath.asPath,
        ),
    )
}
