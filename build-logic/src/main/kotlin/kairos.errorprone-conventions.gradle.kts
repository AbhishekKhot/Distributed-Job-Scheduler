// Static analysis for shared libraries: Error Prone with NullAway in JSpecify mode. Findings are
// compiler warnings, and -Werror (kairos.java-conventions) turns every one into a build failure.
// The plugin forks javac with the --add-exports/--add-opens flags Error Prone needs on JDK 25.
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    errorprone(libs.findLibrary("errorprone-core").get())
    errorprone(libs.findLibrary("nullaway").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        // Generated sources (Protobuf classes in kairos-proto) are not ours to fix.
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
        // Every Kairos package is null-marked: a reference may be null only where it is
        // annotated @Nullable (IMPLEMENTATION_CHECKLIST section 4.3).
        option("NullAway:AnnotatedPackages", "dev.kairos")
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:TreatGeneratedAsUnannotated", "true")
        if (name == "compileJava") {
            error("NullAway")
        } else {
            // Test code builds fixtures in @BeforeEach methods and asserts on nulls on purpose;
            // the null contract is enforced where it matters, on the library API.
            disable("NullAway")
        }
    }
}
