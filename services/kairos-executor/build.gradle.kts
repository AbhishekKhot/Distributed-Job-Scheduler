plugins {
    id("kairos.service-conventions")
}

description = "Kairos executor: run lock, fenced claim, HTTPS calls to team endpoints"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(project(":libs:kairos-persistence"))
    implementation(project(":libs:kairos-engine"))
    implementation(project(":libs:kairos-messaging"))
    implementation(project(":libs:kairos-http"))
    implementation(project(":libs:kairos-security"))
    implementation("org.apache.kafka:kafka-clients")
    implementation(platform(libs.resilience4j.bom))
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.caffeine)

    testImplementation(project(":libs:kairos-test-support"))
    testImplementation("io.lettuce:lettuce-core")
    testImplementation("org.testcontainers:testcontainers-kafka")
}
