package dev.kairos.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.persistence.PersistenceFixtures;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.fanout.FanOutQuery;
import dev.kairos.persistence.health.DatabaseHealthIndicator;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.TransactionSettings;
import dev.kairos.persistence.tx.CellTransactions;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.testsupport.KairosContainer;
import dev.kairos.testsupport.KairosContainers;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;

/**
 * Starts the Spring configuration with the properties the test containers register, exactly as a service would get
 * them, and checks that every bean works against the databases.
 */
@KairosIntegrationTest(kafka = false, redis = false)
class PersistenceConfigurationIntegrationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PersistenceConfiguration.class);
    private String[] containerProperties;

    @BeforeEach
    void setUp(KairosPostgres postgres) {
        PersistenceFixtures.ensureSchema(postgres);
        List<String> properties = new ArrayList<>();
        KairosContainers.springProperties(Set.of(KairosContainer.POSTGRES))
                .forEach((name, value) -> properties.add(name + "=" + value));
        containerProperties = properties.toArray(String[]::new);
    }

    @Test
    void wiresEveryBeanFromTheDatasourceProperties() {
        runner.withPropertyValues(containerProperties).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DataSourceRegistry.class).cells()).containsOnlyKeys("cell-0", "cell-1");
            assertThat(context.getBean(CellRouter.class)
                            .forId(TestIds.jobId(255))
                            .id())
                    .isEqualTo(1);
            assertThat(context.getBean("controlJdbcClient", JdbcClient.class)
                            .sql("SELECT current_database()")
                            .query(String.class)
                            .single())
                    .isEqualTo("kairos_control");
            String cellDatabase = context.getBean(CellTransactions.class)
                    .execute(
                            TestIds.jobId(0).shard(),
                            jdbc -> jdbc.sql("SELECT current_database()")
                                    .query(String.class)
                                    .single());
            assertThat(cellDatabase).isEqualTo("kairos_cell0");
            Integer one = context.getBean(ControlTransactions.class).execute(jdbc -> 1);
            assertThat(one).isEqualTo(1);
            assertThat(context.getBean(FanOutQuery.class)).isNotNull();
            assertThat(context.getBean(DatabaseHealthIndicator.CONTRIBUTOR_NAME, CompositeHealthContributor.class)
                            .getContributor("cell-1"))
                    .isInstanceOf(DatabaseHealthIndicator.class);
        });
    }

    @Test
    void appliesDefaultsAndOverrides() {
        runner.withPropertyValues(containerProperties)
                .withPropertyValues(
                        "kairos.datasource.pool.maximum-size=4",
                        "kairos.datasource.cells[1].maximum-pool-size=2",
                        "kairos.datasource.transaction.isolation=REPEATABLE_READ",
                        "kairos.datasource.transaction.timeout=3s",
                        "kairos.datasource.cell-map-refresh=10s")
                .run(context -> {
                    PersistenceProperties properties = context.getBean(PersistenceProperties.class);
                    assertThat(properties.controlSettings().maximumPoolSize()).isEqualTo(4);
                    assertThat(properties.cellSettings())
                            .extracting(settings -> settings.maximumPoolSize())
                            .containsExactly(4, 2);
                    assertThat(properties.cellMapRefresh()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(properties.healthTimeout()).isEqualTo(Duration.ofSeconds(2));
                    TransactionSettings defaults = context.getBean(DataSourceRegistry.class)
                            .control()
                            .transactions()
                            .defaults();
                    assertThat(defaults.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
                    assertThat(defaults.timeoutSeconds()).isEqualTo(3);
                    assertThat(properties.databaseOptions().batchSize()).isEqualTo(500);
                    assertThat(properties.toString())
                            .doesNotContain(properties.control().password());
                });
    }

    @Test
    void rejectsAUrlThatIsNotPostgres() {
        runner.withPropertyValues(containerProperties)
                .withPropertyValues("kairos.datasource.cells[0].url=jdbc:mysql://db:3306/cell0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAConfigurationWithoutCells() {
        Map<String, String> controlOnly = KairosContainers.springProperties(Set.of(KairosContainer.POSTGRES));
        List<String> properties = new ArrayList<>();
        controlOnly.forEach((name, value) -> {
            if (name.startsWith("kairos.datasource.control.")) {
                properties.add(name + "=" + value);
            }
        });

        runner.withPropertyValues(properties.toArray(String[]::new))
                .run(context -> assertThat(context).hasFailed());
    }
}
