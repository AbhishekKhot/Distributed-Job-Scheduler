package dev.kairos.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.persistence.PersistenceFixtures;
import dev.kairos.persistence.error.ConstraintConflicts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;

class SettingsTest {

    @Test
    void transactionDefaultsAreReadCommittedWithATenSecondDeadline() {
        TransactionSettings defaults = TransactionSettings.defaults();

        assertThat(defaults.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(defaults.timeoutSeconds()).isEqualTo(10);
        assertThat(defaults.conflicts()).isSameAs(ConstraintConflicts.none());
    }

    @Test
    void transactionWithersReplaceOneSetting() {
        ConstraintConflicts conflicts = ConstraintConflicts.of("x_uk", ErrorCode.JOB_NAME_TAKEN, "Taken.");

        TransactionSettings settings = TransactionSettings.defaults()
                .withIsolation(Isolation.SERIALIZABLE)
                .withTimeout(Duration.ofMillis(2_500))
                .withConflicts(conflicts);

        assertThat(settings.isolation()).isEqualTo(Isolation.SERIALIZABLE);
        assertThat(settings.timeoutSeconds()).isEqualTo(3);
        assertThat(settings.conflicts()).isSameAs(conflicts);
    }

    @Test
    void transactionSettingsRejectServerDefaultIsolationAndSubSecondDeadlines() {
        TransactionSettings defaults = TransactionSettings.defaults();

        assertThatThrownBy(() -> defaults.withIsolation(Isolation.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withTimeout(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseOptionsValidateEveryValue() {
        DatabaseOptions defaults = DatabaseOptions.defaults();
        TransactionSettings tx = TransactionSettings.defaults();

        assertThat(defaults.statementTimeoutSeconds()).isEqualTo(5);
        assertThat(defaults.batchSize()).isEqualTo(500);
        assertThatThrownBy(() -> new DatabaseOptions(
                        -1,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        1,
                        tx))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseOptions(
                        0,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        0,
                        tx))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseOptions(
                        0, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 1, tx))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseOptions(
                        0,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(-1),
                        1,
                        tx))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectionSettingsNeverPrintThePassword() {
        ConnectionSettings settings =
                new ConnectionSettings("jdbc:postgresql://db:5432/kairos_cell0", "sched_app", "s3cr3t-value", 8);

        assertThat(settings.toString()).contains("sched_app").doesNotContain("s3cr3t-value");
    }

    @Test
    void connectionSettingsRejectOtherDatabasesBlankCredentialsAndEmptyPools() {
        assertThatThrownBy(() -> new ConnectionSettings("jdbc:mysql://db/x", "u", "p", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionSettings("jdbc:postgresql://db/x", " ", "p", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionSettings("jdbc:postgresql://db/x", "u", "", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionSettings("jdbc:postgresql://db/x", "u", "p", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseNamesAreShortLowerCaseTokens() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        assertThat(Database.cellName(3)).isEqualTo("cell-3");
        assertThat(PersistenceFixtures.unconnected("cell-0", meters).toString()).isEqualTo("Database[cell-0]");
        assertThatThrownBy(() -> PersistenceFixtures.unconnected("Cell 0", meters))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Database.cellName(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryOfWrapsDatabasesByName() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Database control = PersistenceFixtures.unconnected(Database.CONTROL, meters);
        Database cell0 = PersistenceFixtures.unconnected("cell-0", meters);
        Database cell1 = PersistenceFixtures.unconnected("cell-1", meters);

        try (DataSourceRegistry registry = DataSourceRegistry.of(control, List.of(cell0, cell1))) {
            assertThat(registry.control()).isSameAs(control);
            assertThat(registry.cells()).containsOnlyKeys("cell-0", "cell-1");
            assertThat(registry.cell("cell-1")).containsSame(cell1);
            assertThat(registry.cell("cell-9")).isEmpty();
            assertThat(registry.all()).containsExactly(control, cell0, cell1);
        }
    }

    @Test
    void registryNeedsAtLeastOneUniquelyNamedCell() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Database control = PersistenceFixtures.unconnected(Database.CONTROL, meters);
        Database cell0 = PersistenceFixtures.unconnected("cell-0", meters);
        ConnectionSettings settings = new ConnectionSettings("jdbc:postgresql://db/x", "u", "p", 1);

        assertThatThrownBy(() -> DataSourceRegistry.of(control, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataSourceRegistry.of(control, List.of(cell0, cell0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataSourceRegistry.connect(settings, List.of(), DatabaseOptions.defaults(), meters))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
