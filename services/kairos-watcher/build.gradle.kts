plugins {
    id("kairos.service-conventions")
}

description = "Kairos watcher: shard leases, fire and delayed pollers, outbox relay, recovery sweepers"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(project(":libs:kairos-engine"))
}
