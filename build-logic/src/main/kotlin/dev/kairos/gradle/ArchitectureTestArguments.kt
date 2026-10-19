package dev.kairos.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

/**
 * Tells the shared architecture test which class directories to analyse.
 *
 * The directories are tracked as a classpath input, so the test task reruns when the module's
 * main classes change, while the absolute paths in the JVM argument stay out of the build cache
 * key.
 */
abstract class ArchitectureTestArguments : CommandLineArgumentProvider {
    /** Main class directories of the module under test. */
    @get:Classpath
    abstract val analyzedClasses: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf("-D$CLASSES_PROPERTY=${analyzedClasses.asPath}")

    companion object {
        /** System property read by `dev.kairos.archunit.KairosArchitectureTest`. */
        const val CLASSES_PROPERTY = "kairos.archunit.classes"
    }
}
