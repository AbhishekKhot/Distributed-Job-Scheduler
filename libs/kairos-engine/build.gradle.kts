plugins {
    id("kairos.library-conventions")
}

description = "Execution engine shared by the watcher, executor and state services: planners, run lock, claims, settlement."

dependencies {
    api(project(":libs:kairos-domain"))
    api(project(":libs:kairos-persistence"))
    api(project(":libs:kairos-messaging"))

    testImplementation(project(":libs:kairos-test-support"))
    testRuntimeOnly("ch.qos.logback:logback-classic")
}

tasks.withType<Test>().configureEach {
    // Testcontainers reaches the Docker socket through JNA.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Test>("test") {
    // The integration tests apply the control and cell schemas of the migrations job, including the hash partitions
    // of run_inflight and outbox, so they run against the real tables and constraint names.
    inputs
        .dir(isolated.rootProject.projectDirectory.dir("jobs/kairos-migrations/src/main/resources/db"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
