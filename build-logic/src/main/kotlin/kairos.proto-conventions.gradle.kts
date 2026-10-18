// Conventions for the Protobuf contract module: generates Java classes from src/main/proto.
plugins {
    id("kairos.library-conventions")
    id("com.google.protobuf")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    api(libs.findLibrary("protobuf-java").get())
}

protobuf {
    protoc {
        artifact = libs.findLibrary("protobuf-protoc").get().get().toString()
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Generated code is not ours to lint.
    options.compilerArgs.remove("-Werror")
    options.compilerArgs.remove("-Xlint:all,-processing,-serial")
}
