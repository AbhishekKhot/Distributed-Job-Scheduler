plugins {
    id("kairos.library-conventions")
}

description = "Execution engine shared by the watcher, executor and state services: planners, run lock, claims, settlement."

dependencies {
    api(project(":libs:kairos-domain"))
    api(project(":libs:kairos-persistence"))
    api(project(":libs:kairos-messaging"))
}
