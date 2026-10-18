// Conventions for deployable Spring Boot applications (services, jobs and tools).
plugins {
    id("kairos.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

springBoot {
    // Exposes build information on /actuator/info.
    buildInfo()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // A fixed file name lets the shared Dockerfile find the jar of any module.
    archiveFileName.set("app.jar")
}

tasks.named<Jar>("jar") {
    // Only the executable boot jar is published for applications.
    enabled = false
}
