// Kairos monorepo: four deployable services, shared libraries, batch jobs and test tools.
// Builds run inside the JDK 25 builder image (deploy/docker/Dockerfile and the Makefile),
// so no local JDK or Gradle installation is needed.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kairos"

include(
    // Shared libraries
    ":libs:kairos-domain",
    ":libs:kairos-proto",
    ":libs:kairos-persistence",
    ":libs:kairos-messaging",
    ":libs:kairos-security",
    ":libs:kairos-http",
    ":libs:kairos-engine",
    // Deployable services
    ":services:kairos-api",
    ":services:kairos-watcher",
    ":services:kairos-executor",
    ":services:kairos-state",
    // Batch jobs
    ":jobs:kairos-migrations",
    ":jobs:kairos-maintenance",
    // Test tools
    ":tools:mock-target",
    ":tools:bench-cli",
)
