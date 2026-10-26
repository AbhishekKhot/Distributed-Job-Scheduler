plugins {
    id("kairos.service-conventions")
}

description = "Benchmark CLI: creates load and verifies results"

// These classes are the end-to-end harness itself. They run against a fresh Compose stack
// in T-304 acceptance, outside the unit-test JVM that supplies the JaCoCo execution data.
kairosCoverage {
    excludes.addAll(
        "**/scenario/ScenarioRunner*.class",
        "**/scenario/ScenarioEnvironment*.class",
    )
}

dependencies {
    implementation(project(":libs:kairos-domain"))
    implementation(libs.picocli)
    implementation("tools.jackson.core:jackson-databind")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation(project(":libs:kairos-test-support"))
}
