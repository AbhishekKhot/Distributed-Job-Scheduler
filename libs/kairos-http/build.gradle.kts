plugins {
    id("kairos.library-conventions")
}

description = "Outbound HTTP: target policy, validating resolver, TLS client, response limits, target auth and redaction."

dependencies {
    api(project(":libs:kairos-domain"))

    // Types that appear in this module's public API: the Apache HttpClient 5 resolver contract, the decoded JSON tree
    // of responses, the Spring configuration and the configuration properties annotations.
    api("org.apache.httpcomponents.client5:httpclient5")
    api("tools.jackson.core:jackson-databind")
    api("org.springframework:spring-context")
    api("org.springframework.boot:spring-boot")
    api("jakarta.validation:jakarta.validation-api")

    implementation(libs.caffeine)

    testImplementation(project(":libs:kairos-test-support"))
    // Test certificates are generated at test time; no private key is committed.
    testImplementation(libs.bouncycastle)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    // Binds the configuration properties with Bean Validation, as the services do.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-validation")
}
