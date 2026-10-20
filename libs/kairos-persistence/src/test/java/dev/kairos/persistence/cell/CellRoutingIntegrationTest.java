package dev.kairos.persistence.cell;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.persistence.PersistenceFixtures;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Routes ids against the real {@code cell_map} and checks which physical database answers. */
@KairosIntegrationTest(kafka = false, redis = false)
class CellRoutingIntegrationTest {

    private CellRouter router;

    @BeforeEach
    void setUp(KairosPostgres postgres) {
        DataSourceRegistry registry =
                PersistenceFixtures.registry(postgres, DatabaseOptions.defaults(), new SimpleMeterRegistry());
        router = new CellRouter(
                CellMapSource.fromControl(registry.control()),
                registry.cells(),
                CellRouter.DEFAULT_REFRESH_INTERVAL,
                Clock.systemUTC());
    }

    @ParameterizedTest
    @CsvSource({"0, kairos_cell0", "127, kairos_cell0", "128, kairos_cell1", "255, kairos_cell1"})
    void routesIdsToTheDatabaseThatHoldsTheirShard(int shard, String expectedDatabase) {
        UUID runId = TestIds.runId(shard).value();

        String database = router.forId(runId)
                .jdbc()
                .sql("SELECT current_database()")
                .query(String.class)
                .single();

        assertThat(database).isEqualTo(expectedDatabase);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "127, 0", "128, 1", "255, 1"})
    void readsTheLayoutFromCellMap(int shard, int expectedCell) {
        Cell cell = router.forShard(shard);

        assertThat(cell.id()).isEqualTo(expectedCell);
        assertThat(cell.status()).isEqualTo(CellStatus.ACTIVE);
        assertThat(cell.database().name()).isEqualTo("cell-" + expectedCell);
    }
}
