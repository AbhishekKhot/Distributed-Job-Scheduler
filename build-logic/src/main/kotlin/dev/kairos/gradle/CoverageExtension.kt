package dev.kairos.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.math.BigDecimal

/**
 * Coverage settings of one module, exposed as `kairosCoverage { ... }` by
 * `kairos.coverage-conventions`. The module type conventions set the defaults (70% for
 * applications, 80% for libraries, IMPLEMENTATION_CHECKLIST section 3), so build files only
 * touch this to add exclusions.
 */
abstract class CoverageExtension {
    /** Minimum ratio of covered lines, between 0 and 1, enforced by `jacocoTestCoverageVerification`. */
    abstract val minimumLineCoverage: Property<BigDecimal>

    /** Class file patterns (relative to the classes directory) left out of reports and verification. */
    abstract val excludes: ListProperty<String>
}
