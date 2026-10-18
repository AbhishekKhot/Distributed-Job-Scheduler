plugins {
    id("kairos.library-conventions")
}

description = "Kafka producers and consumers, topic names and Protobuf serialisation."

dependencies {
    api(project(":libs:kairos-domain"))
    api(project(":libs:kairos-proto"))
}
