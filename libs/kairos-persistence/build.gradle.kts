plugins {
    id("kairos.library-conventions")
}

description = "PostgreSQL access: data sources, cell routing, transactions, error translation and fan-out reads."

dependencies {
    api(project(":libs:kairos-domain"))

    // Types that appear in this module's public API: JdbcClient and the transaction isolation enum,
    // the meter registry, the health contributor and the configuration properties annotations.
    api("org.springframework:spring-jdbc")
    api("io.micrometer:micrometer-core")
    api("org.springframework.boot:spring-boot")
    api("org.springframework.boot:spring-boot-health")
    api("jakarta.validation:jakarta.validation-api")

    // Health carries Jackson annotations; javac needs them to read its class file without a warning.
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")

    implementation("com.zaxxer:HikariCP")
    implementation(libs.caffeine)
    // The driver is needed at compile time to read the violated constraint from PSQLException.
    implementation("org.postgresql:postgresql")

    testImplementation(project(":libs:kairos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    // Binds the configuration properties with Bean Validation, as the services do.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-validation")
}

tasks.withType<Test>().configureEach {
    // Testcontainers reaches the Docker socket through JNA.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
