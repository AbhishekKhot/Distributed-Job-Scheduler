plugins {
    id("kairos.library-conventions")
}

description = "Kafka producers and consumers, topic names and Protobuf serialisation."

dependencies {
    api(project(":libs:kairos-domain"))
    api(project(":libs:kairos-proto"))

    // Types that appear in this module's public API: the Kafka client, the meter registry, the Micrometer Tracing
    // propagator, the Spring configuration and the configuration properties annotations.
    api("org.apache.kafka:kafka-clients")
    api("io.micrometer:micrometer-core")
    api("io.micrometer:micrometer-tracing")
    api("org.springframework:spring-context")
    api("org.springframework.boot:spring-boot")
    api("jakarta.validation:jakarta.validation-api")

    testImplementation(project(":libs:kairos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-test")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    // Binds the configuration properties with Bean Validation, as the services do.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-validation")
}

tasks.withType<Test>().configureEach {
    // Testcontainers reaches the Docker socket through JNA.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Test>("test") {
    // The topic tests and the Kafka test container read the compose topic script.
    inputs
        .file(isolated.rootProject.projectDirectory.file("deploy/compose/kafka/create-topics.sh"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
