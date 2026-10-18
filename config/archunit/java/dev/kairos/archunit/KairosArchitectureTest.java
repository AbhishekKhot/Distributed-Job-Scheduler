package dev.kairos.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the main classes of the module under test against {@link KairosArchitectureRules}.
 *
 * <p>kairos.archunit-conventions compiles this class into every module's test source set and passes
 * the module's main class directories in the {@value #CLASSES_PROPERTY} system property. Only those
 * classes are imported, so each module answers for its own code, while dependencies on classes of
 * other modules are still visible to the rules.
 */
class KairosArchitectureTest {

    private static final String CLASSES_PROPERTY = "kairos.archunit.classes";

    private static JavaClasses mainClasses;

    @BeforeAll
    static void importMainClasses() {
        String classesDirs = System.getProperty(CLASSES_PROPERTY);
        if (classesDirs == null || classesDirs.isBlank()) {
            throw new IllegalStateException(
                    CLASSES_PROPERTY + " is not set; run the architecture tests through Gradle's test task");
        }
        // A module without compiled main classes (only package-info files) has no directory yet.
        List<Path> existing = Arrays.stream(classesDirs.split(File.pathSeparator))
                .map(Path::of)
                .filter(Files::isDirectory)
                .toList();
        mainClasses = new ClassFileImporter().importPaths(existing);
    }

    @Test
    void domainHasNoFrameworkDependencies() {
        KairosArchitectureRules.DOMAIN_HAS_NO_FRAMEWORK_DEPENDENCIES.check(mainClasses);
    }

    @Test
    void domainUsesOnlyAllowedLibraries() {
        KairosArchitectureRules.DOMAIN_USES_ONLY_ALLOWED_LIBRARIES.check(mainClasses);
    }

    @Test
    void protoUsesOnlyProtobuf() {
        KairosArchitectureRules.PROTO_USES_ONLY_PROTOBUF.check(mainClasses);
    }

    @Test
    void librariesDoNotDependOnApplications() {
        KairosArchitectureRules.LIBRARIES_DO_NOT_DEPEND_ON_APPLICATIONS.check(mainClasses);
    }

    @Test
    void applicationsDoNotDependOnEachOther() {
        KairosArchitectureRules.APPLICATIONS_DO_NOT_DEPEND_ON_EACH_OTHER.check(mainClasses);
    }

    @Test
    void webDoesNotDependOnInfrastructure() {
        KairosArchitectureRules.WEB_DOES_NOT_DEPEND_ON_INFRASTRUCTURE.check(mainClasses);
    }

    @Test
    void applicationLayerIsFrameworkFree() {
        KairosArchitectureRules.APPLICATION_LAYER_IS_FRAMEWORK_FREE.check(mainClasses);
    }

    @Test
    void webDoesNotUseDataClients() {
        KairosArchitectureRules.WEB_DOES_NOT_USE_DATA_CLIENTS.check(mainClasses);
    }

    @Test
    void packagesAreFreeOfCycles() {
        KairosArchitectureRules.NO_PACKAGE_CYCLES.check(mainClasses);
    }
}
