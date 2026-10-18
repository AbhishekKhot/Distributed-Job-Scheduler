// Line coverage for one module: JaCoCo runs with the unit tests, writes HTML and XML reports and
// fails `check` when line coverage drops below the module's minimum (IMPLEMENTATION_CHECKLIST
// section 3). The root project aggregates every module's results into one report.
import dev.kairos.gradle.CoverageExtension
import java.math.BigDecimal

plugins {
    java
    jacoco
}

val libs = the<VersionCatalogsExtension>().named("libs")

val coverage =
    extensions.create<CoverageExtension>("kairosCoverage").apply {
        // Applications (services, jobs and tools) default to 70%; kairos.library-conventions
        // raises the default to 80%.
        minimumLineCoverage.convention(BigDecimal("0.70"))
        // The verification rule below reads the minimum once; a later change would be silently
        // ignored, so it fails loudly instead.
        minimumLineCoverage.finalizeValueOnRead()
        // Boot entry points only call SpringApplication.run; the context tests start them, but
        // their single line says nothing about the module's coverage. Set (not a convention) so
        // other conventions can add to the list.
        excludes.set(listOf("**/*Application.class", "**/*Application$*.class"))
    }

jacoco {
    // Needs a JaCoCo release that understands Java 25 class files.
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

// Resolved lazily, so conventions applied after this plugin can still add exclusions.
val mainClasses =
    files(
        sourceSets.main.map { main ->
            main.output.classesDirs.asFileTree
                .matching { exclude(coverage.excludes.get()) }
        },
    )

tasks.named<JacocoReport>("jacocoTestReport") {
    classDirectories.setFrom(mainClasses)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(mainClasses)
    violationRules {
        rule {
            // A module without measurable classes has no lines, and JaCoCo skips limits whose
            // ratio is undefined, so empty skeleton modules pass.
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = coverage.minimumLineCoverage.get()
            }
        }
    }
}

tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
