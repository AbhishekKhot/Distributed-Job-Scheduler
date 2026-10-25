plugins {
    id("kairos.service-conventions")
}

description = "Kairos state processor: settles results in batches"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(project(":libs:kairos-engine"))
    testImplementation(project(":libs:kairos-test-support"))
    testImplementation("org.testcontainers:testcontainers")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Test>("test") {
    inputs
        .dir(isolated.rootProject.projectDirectory.dir("jobs/kairos-migrations/src/main/resources/db"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
