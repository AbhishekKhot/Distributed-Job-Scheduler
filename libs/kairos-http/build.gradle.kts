plugins {
    id("kairos.library-conventions")
}

description = "Outbound HTTP: target policy, validating resolver, request rendering and outcome classification."

dependencies {
    api(project(":libs:kairos-domain"))
}
