package dev.kairos.migrations.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.migrations.schema.DatabaseKind;
import dev.kairos.migrations.schema.MigrationTarget;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Binding and validation of the job configuration, using the environment variable names of the deployment. */
class MigrationPropertiesTest {

    private static final String PASSWORD = "not-a-real-password";

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(MigrationConfiguration.class);

    @Test
    void bindsTheDeploymentVariablesWithDefaults() {
        Map<String, Object> environment = Map.of(
                "KAIROS_CONTROL_DB_URL", "jdbc:postgresql://postgres:5432/kairos_control",
                "KAIROS_CELL_0_DB_URL", "jdbc:postgresql://postgres:5432/kairos_cell0",
                "KAIROS_CELL_1_DB_URL", "jdbc:postgresql://postgres:5432/kairos_cell1",
                "KAIROS_DB_USERNAME", "sched_owner",
                "KAIROS_DB_PASSWORD", PASSWORD);
        // Replaces the process environment, so the relaxed binding of environment variable names applies.
        context.withInitializer(started -> started.getEnvironment()
                        .getPropertySources()
                        .replace(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                new SystemEnvironmentPropertySource(
                                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    MigrationProperties properties = started.getBean(MigrationProperties.class);
                    assertThat(properties.cell())
                            .extracting(cell -> cell.db().url())
                            .containsExactly(
                                    "jdbc:postgresql://postgres:5432/kairos_cell0",
                                    "jdbc:postgresql://postgres:5432/kairos_cell1");
                    assertThat(properties.migrations().appRole()).isEqualTo("sched_app");
                    assertThat(properties.migrations().partitionDaysAhead()).isEqualTo(3);
                });
    }

    @Test
    void rejectsAUrlThatIsNotAPlainPostgresUrl() {
        context.withPropertyValues(
                        "kairos.control.db.url=jdbc:postgresql://postgres:5432/kairos_control?password=x",
                        "kairos.cell[0].db.url=jdbc:postgresql://postgres:5432/kairos_cell0",
                        "kairos.db.username=sched_owner",
                        "kairos.db.password=" + PASSWORD)
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void requiresAtLeastOneCell() {
        context.withPropertyValues(
                        "kairos.control.db.url=jdbc:postgresql://postgres:5432/kairos_control",
                        "kairos.db.username=sched_owner",
                        "kairos.db.password=" + PASSWORD)
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void credentialsAndTargetsNeverPrintThePassword() {
        MigrationProperties.Credentials credentials = new MigrationProperties.Credentials("sched_owner", PASSWORD);
        MigrationTarget target = MigrationTarget.of(
                DatabaseKind.CELL, "cell 0", "jdbc:postgresql://postgres:5432/kairos_cell0", "sched_owner", PASSWORD);

        assertThat(credentials.toString()).contains("sched_owner").doesNotContain(PASSWORD);
        assertThat(target.toString()).contains("cell 0").doesNotContain(PASSWORD);
    }
}
