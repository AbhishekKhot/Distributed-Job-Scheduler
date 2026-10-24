plugins {
    id("kairos.service-conventions")
}

description = "Kairos watcher: shard leases, fire and delayed pollers, outbox relay, recovery sweepers"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(project(":libs:kairos-persistence"))
    implementation(project(":libs:kairos-engine"))
    implementation(project(":libs:kairos-messaging"))
    implementation("org.apache.kafka:kafka-clients")
    implementation("io.lettuce:lettuce-core")
    implementation("org.postgresql:postgresql")

    testImplementation(project(":libs:kairos-test-support"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
