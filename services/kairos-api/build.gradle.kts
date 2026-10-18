plugins {
    id("kairos.service-conventions")
}

description = "Kairos public REST API"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.springdoc.webmvc.ui)
    implementation(project(":libs:kairos-domain"))
    implementation(project(":libs:kairos-persistence"))
    implementation(project(":libs:kairos-messaging"))
    implementation(project(":libs:kairos-security"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
