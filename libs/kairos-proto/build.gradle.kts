plugins {
    id("kairos.proto-conventions")
}

description = "Protobuf contracts for Kafka messages (dispatch, result, cancel and events)."

// protoc writes the descriptor set of the main sources here, with comments (source info) so the
// tests can check that every message and field is documented.
val descriptorSet = layout.buildDirectory.file("descriptors/kairos-v1.binpb")

// The released contracts. The compatibility check fails the build when the current sources break
// them (removed, renumbered or retyped fields; IMPLEMENTATION_CHECKLIST section 4.7).
val compatBaseline = layout.projectDirectory.file("compat/kairos-v1.txtpb")

protobuf {
    generateProtoTasks {
        ofSourceSet("main").configureEach {
            generateDescriptorSet = true
            descriptorSetOptions.path = descriptorSet.get().asFile.path
            descriptorSetOptions.includeSourceInfo = true
            descriptorSetOptions.includeImports = false
        }
    }
}

val generateProto = tasks.named("generateProto")

val protoCompatibilityCheck =
    tasks.register<JavaExec>("protoCompatibilityCheck") {
        group = "verification"
        description = "Fails when the Protobuf contracts break the released baseline in compat/."
        dependsOn(generateProto)
        inputs.file(compatBaseline)
        inputs.file(descriptorSet)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "dev.kairos.proto.compat.ProtoCompatibilityCheck"
        args("check", compatBaseline.asFile.path, descriptorSet.get().asFile.path)
    }

tasks.register<JavaExec>("updateProtoBaseline") {
    group = "build"
    description = "Writes the current Protobuf contracts to compat/ as the released baseline."
    dependsOn(generateProto)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.kairos.proto.compat.ProtoCompatibilityCheck"
    args("update", descriptorSet.get().asFile.path, compatBaseline.asFile.path)
}

tasks.named("check") {
    dependsOn(protoCompatibilityCheck)
}

tasks.named<Test>("test") {
    dependsOn(generateProto)
    inputs.file(descriptorSet)
    inputs.file(compatBaseline)
    systemProperty("kairos.proto.descriptorSet", descriptorSet.get().asFile.path)
    systemProperty("kairos.proto.baseline", compatBaseline.asFile.path)
}
