dependencies {
    api(project(":chorus-core"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
