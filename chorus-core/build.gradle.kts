dependencies {
    implementation(libs.snakeyaml)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
