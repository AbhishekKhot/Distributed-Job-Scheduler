// Settings shared by every Java module: toolchain, strict compilation, tests, locked dependencies
// and the quality gates every module must pass (formatting, coverage, architecture tests).
plugins {
    java
    id("kairos.dependency-locking")
    id("kairos.formatting-conventions")
    id("kairos.coverage-conventions")
    id("kairos.archunit-conventions")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

group = "dev.kairos"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion))
    }
}

dependencies {
    // All modules share the Spring Boot BOM so library versions never drift between services.
    implementation(platform(lib("spring-boot-bom")))
    annotationProcessor(platform(lib("spring-boot-bom")))
    testImplementation(platform(lib("spring-boot-bom")))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(
        libs
            .findVersion("java")
            .get()
            .requiredVersion
            .toInt(),
    )
    // -parameters keeps parameter names for Spring binding; warnings fail the build.
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // CDS is off because the Mockito agent appends to the boot class path.
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
