plugins {
    id("kairos.service-conventions")
}

description = "Kairos maintenance job and operator commands"

dependencies {
    implementation(project(":libs:kairos-persistence"))
    implementation(project(":libs:kairos-security"))
    implementation(libs.picocli)
    testImplementation(project(":libs:kairos-test-support"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Test>("test") {
    inputs
        .dir(isolated.rootProject.projectDirectory.dir("jobs/kairos-migrations/src/main/resources/db"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
