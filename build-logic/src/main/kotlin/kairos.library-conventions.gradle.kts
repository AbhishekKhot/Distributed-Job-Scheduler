// Conventions for shared libraries under libs/: plain Java libraries with an explicit, documented
// and null-checked API, held to a higher coverage bar than applications.
import dev.kairos.gradle.CoverageExtension
import java.math.BigDecimal

plugins {
    id("kairos.java-conventions")
    `java-library`
    id("kairos.errorprone-conventions")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    // @Nullable and @NullMarked appear in public signatures, so consumers need them too.
    api(libs.findLibrary("jspecify").get())
}

configure<CoverageExtension> {
    minimumLineCoverage.convention(BigDecimal("0.80"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        // Every doclint group, including "missing": an undocumented public type or method in a
        // library fails the build (IMPLEMENTATION_CHECKLIST section 4.4).
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
        quiet()
    }
    // javadoc refuses to run when a module has nothing but package-info files, which is the case
    // for every library until its first class lands.
    onlyIf("the module has documentable classes") { task ->
        (task as Javadoc).source.any { it.name != "package-info.java" }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}
