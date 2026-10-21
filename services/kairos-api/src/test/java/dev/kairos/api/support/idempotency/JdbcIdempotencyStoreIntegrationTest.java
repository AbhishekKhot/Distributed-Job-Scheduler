package dev.kairos.api.support.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.support.idempotency.IdempotencyStore.Location;
import dev.kairos.api.support.idempotency.infrastructure.JdbcIdempotencyStore;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.cell.CellMapSource;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** {@link JdbcIdempotencyStore} on the real {@code api_idempotency} tables of the control database and both cells. */
@KairosIntegrationTest(kafka = false, redis = false)
class JdbcIdempotencyStoreIntegrationTest {

    private static JdbcIdempotencyStore store;

    private final TeamId team = TestIds.teamId();
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeAll
    static void databases() {
        KairosPostgres postgres = ApiTestFixtures.ensureDatabases();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Database control =
                Database.of(Database.CONTROL, postgres.controlDataSource(), DatabaseOptions.defaults(), meters);
        List<Database> cells = new ArrayList<>();
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            cells.add(Database.of(
                    Database.cellName(cell), postgres.cellDataSource(cell), DatabaseOptions.defaults(), meters));
        }
        DataSourceRegistry registry = DataSourceRegistry.of(control, cells);
        CellRouter router = new CellRouter(
                CellMapSource.fromControl(registry.control()),
                registry.cells(),
                CellRouter.DEFAULT_REFRESH_INTERVAL,
                Clock.systemUTC());
        store = new JdbcIdempotencyStore(
                JdbcClient.create(postgres.controlDataSource()),
                router,
                JsonMapper.builder().build());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 127, 128, 255})
    void aRecordIsClaimedCompletedAndReplayedByteForByte(int shard) {
        Location location = shard < 0 ? Location.CONTROL : Location.cell(Shard.of(shard));
        String key = UUID.randomUUID().toString();
        byte[] hash = {1, 2, 3};
        String body = "{\"zeta\":1,\"alpha\":[true,null],\"id\":\"x\"}";
        StoredResponse response =
                new StoredResponse(201, "application/json", Map.of("Location", "/v1/jobs/x", "ETag", "\"1\""), body);

        assertThat(store.insert(location, team, key, hash, now)).isTrue();
        assertThat(store.insert(location, team, key, hash, now)).isFalse();
        assertThat(store.find(location, team, key).orElseThrow().done()).isFalse();
        assertThat(store.complete(location, team, key, now, response)).isTrue();

        IdempotencyStore.StoredRecord stored = store.find(location, team, key).orElseThrow();
        assertThat(stored.done()).isTrue();
        assertThat(stored.requestHash()).isEqualTo(hash);
        assertThat(stored.createdAt()).isEqualTo(now);
        assertThat(stored.response()).isEqualTo(response);
        assertThat(stored.response().body()).isEqualTo(body);
    }

    @Test
    void releaseAndTakeOverOnlyAffectTheirOwnClaim() {
        Location location = Location.cell(Shard.of(42));
        String key = UUID.randomUUID().toString();
        store.insert(location, team, key, new byte[] {1}, now);

        store.release(location, team, key, now.plusSeconds(1));
        assertThat(store.find(location, team, key)).isPresent();

        Instant later = now.plusSeconds(120);
        assertThat(store.takeOver(location, team, key, now.minusSeconds(1), new byte[] {2}, later))
                .isFalse();
        assertThat(store.takeOver(location, team, key, now, new byte[] {2}, later))
                .isTrue();
        assertThat(store.complete(location, team, key, now, new StoredResponse(200, null, Map.of(), null)))
                .isFalse();
        assertThat(store.find(location, team, key).orElseThrow().requestHash()).isEqualTo(new byte[] {2});

        store.release(location, team, key, later);
        assertThat(store.find(location, team, key)).isEmpty();
    }

    @Test
    void recordsAreScopedByTeam() {
        Location location = Location.CONTROL;
        String key = UUID.randomUUID().toString();
        store.insert(location, team, key, new byte[] {1}, now);

        assertThat(store.find(location, TestIds.teamId(), key)).isEmpty();
    }
}
