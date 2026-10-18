plugins {
    `kotlin-dsl`
}

dependencies {
    // Third-party plugins applied by the convention plugins must be on this build's classpath.
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.protobuf.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.errorprone.gradle.plugin)
}
