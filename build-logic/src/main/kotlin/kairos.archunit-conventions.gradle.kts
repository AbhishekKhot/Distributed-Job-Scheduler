// Architecture tests for one module. The shared rule set in config/archunit (the rules of
// IMPLEMENTATION_CHECKLIST section 4.1) is compiled into the module's test source set and runs
// in its `test` task against the module's own main classes, so no module can opt out and new
// modules are covered without writing a test.
import dev.kairos.gradle.ArchitectureTestArguments

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

sourceSets.named("test") {
    java.srcDir(isolated.rootProject.projectDirectory.dir("config/archunit/java"))
}

dependencies {
    testImplementation(libs.findLibrary("archunit").get())
}

tasks.named<Test>("test") {
    jvmArgumentProviders.add(
        objects.newInstance<ArchitectureTestArguments>().apply {
            analyzedClasses.from(sourceSets.main.map { it.output.classesDirs })
        },
    )
}
