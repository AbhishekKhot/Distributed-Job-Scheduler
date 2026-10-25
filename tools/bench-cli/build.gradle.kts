plugins {
    id("kairos.service-conventions")
}

description = "Benchmark CLI: creates load and verifies results"

dependencies {
    implementation(project(":libs:kairos-domain"))
    implementation(libs.picocli)
    implementation("tools.jackson.core:jackson-databind")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation(project(":libs:kairos-test-support"))
}
