plugins {
    id("kairos.service-conventions")
}

description = "Kairos database migrations job"

dependencies {
    implementation(project(":libs:kairos-persistence"))
}
