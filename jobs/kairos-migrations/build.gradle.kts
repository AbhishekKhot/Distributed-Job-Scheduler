plugins {
    id("kairos.service-conventions")
}

description = "Kairos database migrations job"

dependencies {
    implementation(project(":libs:kairos-persistence"))
    // Creates the platform access-token signing keyset after the control schema is migrated.
    implementation(project(":libs:kairos-security"))
    // Flyway is driven directly, one instance per database; the Spring Boot Flyway
    // auto-configuration, which assumes a single data source, is deliberately not on the classpath.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.postgresql:postgresql")

    testImplementation(project(":libs:kairos-test-support"))
}

tasks.withType<Test>().configureEach {
    // Testcontainers reaches the Docker socket through JNA.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
