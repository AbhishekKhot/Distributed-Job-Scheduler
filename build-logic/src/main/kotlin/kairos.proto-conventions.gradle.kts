// Conventions for the Protobuf contract module: generates Java classes from src/main/proto.
import dev.kairos.gradle.CoverageExtension

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
        artifact =
            libs
                .findLibrary("protobuf-protoc")
                .get()
                .get()
                .toString()
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Generated code is not ours to lint.
    options.compilerArgs.remove("-Werror")
    options.compilerArgs.remove("-Xlint:all,-processing,-serial")
}

// Generated messages live in versioned packages (dev.kairos.proto.v1, ...); hand-written helpers
// stay in dev.kairos.proto. Only the hand-written part is measured and documented.
configure<CoverageExtension> {
    excludes.add("dev/kairos/proto/v*/**")
}

tasks.named<Javadoc>("javadoc") {
    exclude("dev/kairos/proto/v*/**")
}
