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
    // Exposes build information on /actuator/info. The build time is left out: it changes on
    // every run, which would invalidate the resources, the boot jar and every test of the module,
    // so warm builds could never be up to date or come from the build cache.
    buildInfo {
        excludes.set(setOf("time"))
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // A fixed file name lets the shared Dockerfile find the jar of any module.
    archiveFileName.set("app.jar")
}

tasks.named<Jar>("jar") {
    // Only the executable boot jar is published for applications.
    enabled = false
}

tasks.withType<Javadoc>().configureEach {
    // Applications need class-level Javadoc on components only (IMPLEMENTATION_CHECKLIST
    // section 4.4), so missing comments on members are not reported.
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}
