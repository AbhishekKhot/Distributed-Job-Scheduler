plugins {
    id("kairos.service-conventions")
}

description = "Kairos maintenance job and operator commands"

dependencies {
    implementation(project(":libs:kairos-persistence"))
    implementation(project(":libs:kairos-security"))
}
