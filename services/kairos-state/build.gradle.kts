plugins {
    id("kairos.service-conventions")
}

description = "Kairos state processor: settles results in batches"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(project(":libs:kairos-engine"))
}
