plugins {
    id("kairos.service-conventions")
}

description = "Mock HTTPS target for tests and benchmarks"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
