plugins {
    id("kairos.service-conventions")
}

description = "Benchmark CLI: creates load and verifies results"

dependencies {
    implementation(project(":libs:kairos-domain"))
}
