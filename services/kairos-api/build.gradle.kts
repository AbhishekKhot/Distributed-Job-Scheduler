plugins {
    id("kairos.service-conventions")
}

description = "Kairos public REST API"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation(libs.springdoc.webmvc.ui)
    implementation(project(":libs:kairos-domain"))
    implementation(project(":libs:kairos-persistence"))
    implementation(project(":libs:kairos-messaging"))
    implementation(project(":libs:kairos-security"))
    // The process's single id factory (OutboxConfiguration) and the Redis connection settings (kairos.redis.*).
    implementation(project(":libs:kairos-engine"))
    implementation(project(":libs:kairos-http"))

    // Rate limits: Bucket4j token buckets in Redis through Lettuce (kairos:rl:*).
    implementation(libs.bucket4j.lettuce)
    implementation("io.lettuce:lettuce-core")
    // Session and API key lookup caches.
    implementation(libs.caffeine)
    // IP literal parsing without DNS lookups, for the trusted-proxy check.
    implementation(libs.guava)
    implementation("org.postgresql:postgresql")

    testImplementation(project(":libs:kairos-test-support"))
    // Throwaway CA and invalid-certificate fixtures; private keys never leave test memory.
    testImplementation(libs.bouncycastle)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.testcontainers:testcontainers")
}

tasks.withType<Test>().configureEach {
    // Testcontainers reaches the Docker socket through JNA.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val openApiDocument = layout.buildDirectory.file("openapi/kairos-v1.json")

tasks.named<Test>("test") {
    // The integration tests apply the control and cell schemas of the migrations job, and the OpenAPI test reads the
    // LLD's operation list.
    inputs
        .dir(isolated.rootProject.projectDirectory.dir("jobs/kairos-migrations/src/main/resources/db"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .file(isolated.rootProject.projectDirectory.file("LLD.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("kairos.openapi.output", openApiDocument.get().asFile.absolutePath)
    outputs.file(openApiDocument)
}

// Writes the OpenAPI document of the running application to build/openapi/kairos-v1.json (IMPLEMENTATION_CHECKLIST
// section 4.5). The document is produced by the test that starts the full application and checks it against the LLD.
tasks.register<Test>("openApiExport") {
    description = "Writes the OpenAPI document to build/openapi/kairos-v1.json."
    group = "documentation"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("dev.kairos.api.config.OpenApiDocumentTest") }
    inputs
        .file(isolated.rootProject.projectDirectory.file("LLD.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("kairos.openapi.output", openApiDocument.get().asFile.absolutePath)
    outputs.file(openApiDocument)
}
