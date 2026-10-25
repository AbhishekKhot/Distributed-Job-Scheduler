// Conventions for the root project: formatting of the files that belong to no module (build
// scripts, build-logic, the shared architecture rules) and the coverage report aggregated over
// every module.
plugins {
    base
    jacoco
    `jacoco-report-aggregation`
    id("kairos.dependency-locking")
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

spotless {
    java {
        target("config/**/*.java")
        palantirJavaFormat(version("palantir-java-format"))
        formatAnnotations()
    }
    kotlinGradle {
        // Module build scripts are formatted by their own module (kairos.formatting-conventions).
        target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        ktlint(version("ktlint"))
    }
    kotlin {
        target("build-logic/src/**/*.kt")
        ktlint(version("ktlint"))
    }
}

jacoco {
    toolVersion = version("jacoco")
}

dependencies {
    // Every leaf module; the grouping projects (:libs, :services, ...) have no code.
    subprojects.filter { it.childProjects.isEmpty() }.forEach { jacocoAggregation(project(it.path)) }
}

reporting {
    reports {
        register<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName = "test"
        }
    }
}

tasks.named<JacocoReport>("testCodeCoverageReport") {
    // Same exclusions as the per-module reports: generated Protobuf classes and boot entry points.
    // Copy the current sources first: getFrom() is a live view, and filtering it in place would
    // make the collection contain itself.
    val aggregated = objects.fileCollection().from(classDirectories.from.toList())
    classDirectories.setFrom(
        aggregated.asFileTree.matching {
            exclude("**/*Application.class", "**/*Application$*.class", "dev/kairos/proto/v*/**")
        },
    )
}

tasks.named("check") {
    dependsOn(tasks.named("testCodeCoverageReport"))
    dependsOn(
        ":services:kairos-api:integrationTest",
        ":services:kairos-watcher:integrationTest",
        ":services:kairos-executor:integrationTest",
        ":services:kairos-state:integrationTest",
    )
}
