plugins {
    id("kairos.library-conventions")
}

description =
    "Shared test fixtures: Postgres, Kafka and Redis test containers pinned to the compose images, " +
    "the @KairosIntegrationTest extension, a mutable clock and polling helpers. " +
    "Other modules use it through testImplementation only."

dependencies {
    // The Spring Boot BOM (applied by the conventions) already manages Testcontainers; the
    // Testcontainers BOM is applied as well so its modules always resolve as one version.
    implementation(platform(libs.testcontainers.bom))

    // Types that appear in this module's public API.
    api(project(":libs:kairos-domain"))
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.awaitility:awaitility")

    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:testcontainers-postgresql")
    implementation("org.testcontainers:testcontainers-kafka")
    implementation("com.zaxxer:HikariCP")
    implementation("org.jspecify:jspecify")
    runtimeOnly("org.postgresql:postgresql")

    // Spring support is optional: the context customizer is only loaded by the Spring
    // TestContext framework, which brings these classes with it.
    compileOnly("org.springframework:spring-test")
    compileOnly("org.springframework:spring-context")

    testImplementation("org.apache.kafka:kafka-clients")
    testImplementation("io.lettuce:lettuce-core")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework:spring-context")
    testRuntimeOnly("ch.qos.logback:logback-classic")
}

tasks.withType<Test>().configureEach {
    // Testcontainers reaches the Docker socket through JNA; allowing native access up front avoids the JDK's
    // restricted-method warning on every run.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
