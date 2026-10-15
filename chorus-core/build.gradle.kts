dependencies {
    implementation(libs.snakeyaml)
    implementation(libs.nimbus.jose.jwt)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
