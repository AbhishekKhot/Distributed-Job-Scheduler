// Formatting for one Java module: palantir-java-format for its Java sources and ktlint for its
// build script. spotlessCheck runs in `check` (and therefore in `make build`); the fix is
// `make gradle ARGS=spotlessApply`.
plugins {
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

spotless {
    java {
        // Hand-written sources only; generated code under build/ is never formatted.
        target("src/*/java/**/*.java")
        palantirJavaFormat(version("palantir-java-format"))
        formatAnnotations()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(version("ktlint"))
    }
}
