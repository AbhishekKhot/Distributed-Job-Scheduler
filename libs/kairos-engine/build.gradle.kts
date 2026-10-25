plugins {
    id("kairos.library-conventions")
}

description = "Execution engine shared by the watcher, executor and state services: planners, run lock, claims, settlement."

dependencies {
    api(project(":libs:kairos-domain"))
    api(project(":libs:kairos-persistence"))
    api(project(":libs:kairos-messaging"))

    // The Redis run lock and sent markers (dev.kairos.engine.lock). Lettuce stays behind the ports; only the
    // configuration properties, the Spring configuration and the meter registry appear in the public API.
    implementation("io.lettuce:lettuce-core")
    api("org.springframework:spring-context")
    api("tools.jackson.core:jackson-databind")

    testImplementation(project(":libs:kairos-test-support"))
    // A dedicated Redis container that the fail-closed test stops.
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.springframework.boot:spring-boot-test")
    // Binds the configuration properties with Bean Validation, as the services do.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-validation")
    // The NOTIFY test listens through the driver's PGConnection.
    testImplementation("org.postgresql:postgresql")
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
