package dev.kairos.persistence.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.persistence.jdbc.ConnectionSettings;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.persistence.jdbc.TransactionSettings;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** Pools created by the registry, and the readiness check of each database, up and down. */
@KairosIntegrationTest(kafka = false, redis = false)
class DatabaseHealthIntegrationTest {

    private static final DatabaseOptions FAST_FAILING = new DatabaseOptions(
            0,
            Duration.ofMillis(500),
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofSeconds(5),
            100,
            TransactionSettings.defaults());

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void reportsEveryReachableDatabaseUp(KairosPostgres postgres) {
        try (DataSourceRegistry registry = connect(postgres, postgres.cellJdbcUrl(1))) {
            CompositeHealthContributor composite = DatabaseHealthIndicator.forRegistry(registry, Duration.ofSeconds(2));

            Map<String, Health> health = healthByName(composite);

            assertThat(health).containsOnlyKeys("control", "cell-0", "cell-1");
            assertThat(health.values()).extracting(Health::getStatus).containsOnly(Status.UP);
            assertThat(health.get("cell-1").getDetails()).containsExactly(Map.entry("database", "cell-1"));
        }
    }

    @Test
    void reportsOnlyTheUnreachableCellDown(KairosPostgres postgres) {
        try (DataSourceRegistry registry = connect(postgres, "jdbc:postgresql://127.0.0.1:1/kairos_cell1")) {
            Map<String, Health> health =
                    healthByName(DatabaseHealthIndicator.forRegistry(registry, Duration.ofSeconds(1)));

            assertThat(health.get("control").getStatus()).isEqualTo(Status.UP);
            assertThat(health.get("cell-0").getStatus()).isEqualTo(Status.UP);
            assertThat(health.get("cell-1").getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.get("cell-1").getDetails()).containsOnlyKeys("database");
        }
    }

    @Test
    void createsNamedPoolsWithMetrics(KairosPostgres postgres) {
        try (DataSourceRegistry registry = connect(postgres, postgres.cellJdbcUrl(1))) {
            String database = registry.cell("cell-1")
                    .orElseThrow()
                    .jdbc()
                    .sql("SELECT current_database()")
                    .query(String.class)
                    .single();

            assertThat(database).isEqualTo("kairos_cell1");
            assertThat(meters.find("hikaricp.connections")
                            .tag("pool", "kairos-cell-1")
                            .gauge())
                    .isNotNull();
            assertThat(meters.find("hikaricp.connections.acquire")
                            .tag("pool", "kairos-control")
                            .timer())
                    .isNotNull();
        }
    }

    @Test
    void healthCheckTimeoutIsAtLeastOneSecond(KairosPostgres postgres) {
        Database control = Database.of(Database.CONTROL, postgres.controlDataSource(), FAST_FAILING, meters);

        assertThatThrownBy(() -> new DatabaseHealthIndicator(control, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DataSourceRegistry connect(KairosPostgres postgres, String cell1Url) {
        return DataSourceRegistry.connect(
                settings(postgres, postgres.controlJdbcUrl()),
                List.of(settings(postgres, postgres.cellJdbcUrl(0)), settings(postgres, cell1Url)),
                FAST_FAILING,
                meters);
    }

    private static ConnectionSettings settings(KairosPostgres postgres, String url) {
        return new ConnectionSettings(url, postgres.appUsername(), postgres.appPassword(), 2);
    }

    private static Map<String, Health> healthByName(CompositeHealthContributor composite) {
        Map<String, Health> health = new LinkedHashMap<>();
        composite.stream()
                .forEach(entry -> health.put(entry.name(), ((DatabaseHealthIndicator) entry.contributor()).health()));
        return health;
    }
}
