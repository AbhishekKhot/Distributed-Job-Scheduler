plugins {
    id("kairos.library-conventions")
}

description = "Pure domain model and rules: ids, shards, schedules, retry policy, run state machine, DAG validation, templates."

// No framework dependencies are allowed here (enforced by the architecture tests).
dependencies {
    // Only com.google.common.hash is used (murmur3 for shard keys); the architecture tests reject
    // any other Guava package.
    implementation(libs.guava)

    testImplementation(libs.jqwik)
}

tasks.named<Test>("test") {
    // ErrorCodeTest compares the error catalogue with the problem types documented in the LLD.
    inputs
        .file(isolated.rootProject.projectDirectory.file("LLD.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
