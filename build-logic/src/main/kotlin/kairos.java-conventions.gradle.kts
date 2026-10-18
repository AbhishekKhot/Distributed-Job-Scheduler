// Settings shared by every Java module: toolchain, strict compilation, tests and locked dependencies.
plugins {
    java
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

dependencyLocking {
    // Every resolved configuration is locked; refresh with: ./gradlew resolveAndLockAll --write-locks
    lockAllConfigurations()
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
    options.release.set(libs.findVersion("java").get().requiredVersion.toInt())
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

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

tasks.register("resolveAndLockAll") {
    description = "Resolves every resolvable configuration so --write-locks can record it."
    notCompatibleWithConfigurationCache("Resolves configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}
