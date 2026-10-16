/*
 * @context
 * refs: ARCHITECTURE.md, sections 2.2 and 6
 * related: gradle/libs.versions.toml; AGENTS.md
 * sync: Keep real SQLite integration tests in check and build without external services.
 * @endcontext
 */

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
