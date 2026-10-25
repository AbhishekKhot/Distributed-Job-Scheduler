plugins {
    id("kairos.service-conventions")
}

description = "Mock HTTPS target for tests and benchmarks"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(project(":libs:kairos-security"))
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)
    testImplementation(project(":libs:kairos-http"))
}
