// Observability baseline for one application (IMPLEMENTATION_CHECKLIST section 4.9). The shared
// sources in config/observability (tracing defaults, common metric tags, the schedule-lag SLO
// histogram and log redaction) are compiled into the application, and their tests run in its
// `test` task, so every service behaves the same and no application depends on another.
plugins {
    java
}

val shared = isolated.rootProject.projectDirectory.dir("config/observability")

// Gradle silently skips a missing source directory, which would ship an application without
// tracing defaults or log redaction (an image build context without config/, for example).
check(shared.dir("main/java").asFile.isDirectory) {
    "Missing ${shared.dir("main/java").asFile}: the shared observability sources must be present"
}

sourceSets.named("main") {
    java.srcDir(shared.dir("main/java"))
    resources.srcDir(shared.dir("main/resources"))
}

sourceSets.named("test") {
    java.srcDir(shared.dir("test/java"))
}

dependencies {
    // Micrometer Tracing with the OpenTelemetry bridge and the OTLP exporter. Export stays off
    // unless KAIROS_OBSERVABILITY_TRACING_EXPORT_ENABLED is true.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    // Validates the typed @ConfigurationProperties records of every application.
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
