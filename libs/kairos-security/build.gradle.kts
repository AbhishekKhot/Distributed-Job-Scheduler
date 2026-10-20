plugins {
    id("kairos.library-conventions")
}

description = "Password hashing, access and refresh tokens, API keys, keysets, secret encryption and request signing."

dependencies {
    api(project(":libs:kairos-domain"))

    // Types that appear in this module's public API: the Tink primitives and keyset handles, the control database's
    // JdbcClient (platform keyset store), the Spring configuration and the configuration properties annotations.
    api(libs.tink)
    api("org.springframework:spring-context")
    api("org.springframework:spring-jdbc")
    api("org.springframework.boot:spring-boot")
    api("jakarta.validation:jakarta.validation-api")

    // Argon2id comes from Bouncy Castle's lightweight API; no JCA provider is registered.
    implementation(libs.bouncycastle)
    implementation(libs.nimbus.jose.jwt)
    // Cloud KMS key encryption key, used outside the local profile.
    implementation(libs.tink.gcpkms)
    implementation(libs.caffeine)

    testImplementation(project(":libs:kairos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    // Binds the configuration properties with Bean Validation, as the services do.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-validation")
}

tasks.withType<Test>().configureEach {
    // Testcontainers reaches the Docker socket through JNA.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Test>("test") {
    // The platform keyset store test applies the control schema of the migrations job.
    inputs
        .dir(isolated.rootProject.projectDirectory.dir("jobs/kairos-migrations/src/main/resources/db"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
