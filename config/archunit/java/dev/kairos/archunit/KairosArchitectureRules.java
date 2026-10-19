package dev.kairos.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.List;

/**
 * Architecture rules of IMPLEMENTATION_CHECKLIST section 4.1, shared by every module.
 *
 * <p>Every rule is written against package names rather than modules, so one rule set fits every
 * module: a rule whose packages are absent from a module's classes passes trivially
 * ({@code allowEmptyShould}). That keeps the rules green on the empty skeleton and makes them bite
 * as soon as the first class lands in a matching package.
 */
public final class KairosArchitectureRules {

    /**
     * Root packages of the deployable applications: services, jobs and tools. A new application
     * module adds its root package here.
     */
    public static final List<String> APPLICATION_PACKAGES = List.of(
            "dev.kairos.api",
            "dev.kairos.watcher",
            "dev.kairos.executor",
            "dev.kairos.state",
            "dev.kairos.migrations",
            "dev.kairos.maintenance",
            "dev.kairos.mocktarget",
            "dev.kairos.bench");

    private static final String[] FRAMEWORK_PACKAGES = {
        "org.springframework..",
        "jakarta..",
        "java.sql..",
        "javax.sql..",
        "com.fasterxml..",
        "tools.jackson..",
        "org.apache.kafka..",
        "io.lettuce..",
        "redis.clients.."
    };

    private static final String[] DATA_CLIENT_PACKAGES = {
        "java.sql..",
        "javax.sql..",
        "org.springframework.jdbc..",
        "org.apache.kafka..",
        "org.springframework.kafka..",
        "io.lettuce..",
        "org.springframework.data.redis.."
    };

    private static final String[] WEB_PACKAGES = {
        "org.springframework.web..", "org.springframework.http..", "jakarta.servlet.."
    };

    /** The domain is plain Java: no Spring, Jakarta, JDBC, Jackson, Kafka or Redis types. */
    public static final ArchRule DOMAIN_HAS_NO_FRAMEWORK_DEPENDENCIES = noClasses()
            .that()
            .resideInAPackage("dev.kairos.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FRAMEWORK_PACKAGES)
            .because("kairos-domain is plain Java; frameworks sit at the edges")
            .allowEmptyShould(true);

    /** The domain depends on the JDK, Guava hashing, cron-utils and JSpecify only. */
    public static final ArchRule DOMAIN_USES_ONLY_ALLOWED_LIBRARIES = classes()
            .that()
            .resideInAPackage("dev.kairos.domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..", "dev.kairos.domain..", "com.google.common.hash..", "com.cronutils..", "org.jspecify..")
            .because("kairos-domain depends on nothing but the JDK, Guava hashing and cron-utils")
            .allowEmptyShould(true);

    /** Protobuf contracts depend on protobuf-java only. */
    public static final ArchRule PROTO_USES_ONLY_PROTOBUF = classes()
            .that()
            .resideInAPackage("dev.kairos.proto..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..", "javax.annotation..", "com.google.protobuf..", "dev.kairos.proto..", "org.jspecify..")
            .because("kairos-proto holds message contracts only")
            .allowEmptyShould(true);

    /** Libraries never depend on an application. */
    public static final ArchRule LIBRARIES_DO_NOT_DEPEND_ON_APPLICATIONS = noClasses()
            .that()
            .resideInAPackage("dev.kairos..")
            .and()
            .resideOutsideOfPackages(subpackagesOf(APPLICATION_PACKAGES, ".."))
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(subpackagesOf(APPLICATION_PACKAGES, ".."))
            .because("libraries are shared by every application and cannot know any of them")
            .allowEmptyShould(true);

    /** Applications never depend on each other; shared code belongs in a library. */
    public static final ArchRule APPLICATIONS_DO_NOT_DEPEND_ON_EACH_OTHER = applicationsDoNotDependOnEachOther();

    /** Controllers use application services only, never adapters directly. */
    public static final ArchRule WEB_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAnyPackage(subpackagesOf(APPLICATION_PACKAGES, "..web.."))
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .because("web depends on application, never on infrastructure")
            .allowEmptyShould(true);

    /** Use cases depend on domain types and their own ports, not on Spring web, JDBC, Kafka or Redis. */
    public static final ArchRule APPLICATION_LAYER_IS_FRAMEWORK_FREE = noClasses()
            .that()
            .resideInAnyPackage(subpackagesOf(APPLICATION_PACKAGES, "..application.."))
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(concat(WEB_PACKAGES, DATA_CLIENT_PACKAGES))
            .because("application holds use cases and ports; adapters live in infrastructure")
            .allowEmptyShould(true);

    /** Only infrastructure adapters touch JDBC, Kafka or Redis clients. */
    public static final ArchRule WEB_DOES_NOT_USE_DATA_CLIENTS = noClasses()
            .that()
            .resideInAnyPackage(subpackagesOf(APPLICATION_PACKAGES, "..web.."))
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(DATA_CLIENT_PACKAGES)
            .because("infrastructure is the only layer that touches JdbcClient, KafkaTemplate or Lettuce")
            .allowEmptyShould(true);

    /** No dependency cycles between packages. */
    public static final ArchRule NO_PACKAGE_CYCLES =
            slices().matching("dev.kairos.(**)").should().beFreeOfCycles().allowEmptyShould(true);

    private KairosArchitectureRules() {}

    private static ArchRule applicationsDoNotDependOnEachOther() {
        List<ArchRule> rules = APPLICATION_PACKAGES.stream()
                .map(application -> noClasses()
                        .that()
                        .resideInAPackage(application + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(subpackagesOf(
                                APPLICATION_PACKAGES.stream()
                                        .filter(other -> !other.equals(application))
                                        .toList(),
                                ".."))
                        .because("applications share code through libraries only")
                        .allowEmptyShould(true))
                .toList();
        return CompositeArchRule.of(rules);
    }

    private static String[] subpackagesOf(List<String> roots, String suffix) {
        return roots.stream().map(root -> root + suffix).toArray(String[]::new);
    }

    private static String[] concat(String[] first, String[] second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
