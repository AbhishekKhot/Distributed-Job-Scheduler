package dev.kairos.persistence.cell;

import static dev.kairos.persistence.cell.CellLayoutTest.CELL_0;
import static dev.kairos.persistence.cell.CellLayoutTest.CELL_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.PersistenceFixtures;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CellRouterTest {

    private static final Duration REFRESH = Duration.ofSeconds(30);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Database cell0 = PersistenceFixtures.unconnected("cell-0", meters);
    private final Database cell1 = PersistenceFixtures.unconnected("cell-1", meters);
    private final Database cell2 = PersistenceFixtures.unconnected("cell-2", meters);
    private final Map<String, Database> databases = Map.of("cell-0", cell0, "cell-1", cell1, "cell-2", cell2);
    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
    private final AtomicReference<Supplier<List<CellRange>>> rows =
            new AtomicReference<>(() -> List.of(CELL_0, CELL_1));
    private final AtomicInteger loads = new AtomicInteger();

    // Reloads run on the calling thread, so the tests see their effect without waiting.
    private final CellRouter router = new CellRouter(
            () -> {
                loads.incrementAndGet();
                return rows.get().get();
            },
            databases,
            REFRESH,
            clock,
            Runnable::run);

    @Test
    void routesShardsAtTheCellBoundaries() {
        assertThat(router.forShard(0).database()).isSameAs(cell0);
        assertThat(router.forShard(127).database()).isSameAs(cell0);
        assertThat(router.forShard(128).database()).isSameAs(cell1);
        assertThat(router.forShard(Shard.of(255)).database()).isSameAs(cell1);
    }

    @Test
    void routesIdsByTheirEmbeddedShard() {
        assertThat(router.forId(TestIds.id(0)).id()).isZero();
        assertThat(router.forId(TestIds.runId(127)).id()).isZero();
        assertThat(router.forId(TestIds.jobId(128)).id()).isEqualTo(1);
        assertThat(router.forId(TestIds.dlqId(255)).id()).isEqualTo(1);
    }

    @Test
    void cellsExposeTheirRowAndDatabase() {
        Cell cell = router.forShard(200);

        assertThat(cell.status()).isEqualTo(CellStatus.ACTIVE);
        assertThat(cell.jdbc()).isSameAs(cell1.jdbc());
        assertThat(cell.transactions()).isSameAs(cell1.transactions());
        assertThat(router.layout().ranges()).containsExactly(CELL_0, CELL_1);
    }

    @Test
    void listsAllCellsInCellOrder() {
        assertThat(router.allCells()).extracting(Cell::id).containsExactly(0, 1);
    }

    @Test
    void rejectsInvalidShardsAndIds() {
        assertThatThrownBy(() -> router.forShard(256)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.forShard(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.forId(UUID.fromString("00000000-0000-4000-8000-000000000000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cachesTheLayoutUntilTheRefreshInterval() {
        router.forShard(1);
        router.forShard(200);
        clock.advance(REFRESH.minusSeconds(1));
        router.forShard(3);

        assertThat(loads).hasValue(1);
    }

    @Test
    void picksUpANewLayoutAfterTheRefreshInterval() {
        assertThat(router.forShard(200).id()).isEqualTo(1);
        rows.set(() -> List.of(
                new CellRange(0, 0, 127, "cell-0", CellStatus.ACTIVE),
                new CellRange(1, 128, 191, "cell-1", CellStatus.ACTIVE),
                new CellRange(2, 192, 255, "cell-2", CellStatus.READ_ONLY)));

        clock.advance(REFRESH.plusSeconds(1));
        // The first lookup after the interval triggers the reload; with a direct executor it completes at once.
        router.forShard(0);

        assertThat(router.forShard(200).id()).isEqualTo(2);
        assertThat(router.forShard(200).status()).isEqualTo(CellStatus.READ_ONLY);
        assertThat(router.allCells()).extracting(Cell::id).containsExactly(0, 1, 2);
    }

    @Test
    void keepsTheCurrentLayoutWhenAReloadFails() {
        router.forShard(0);
        rows.set(() -> {
            throw new IllegalStateException("control database down");
        });

        clock.advance(REFRESH.plusSeconds(1));
        router.forShard(0);

        assertThat(router.forShard(200).id()).isEqualTo(1);
        assertThat(loads.get()).isGreaterThan(1);
    }

    @Test
    void keepsTheCurrentLayoutWhenAReloadIsInvalid() {
        router.forShard(0);
        rows.set(() -> List.of(CELL_0));

        clock.advance(REFRESH.plusSeconds(1));
        router.forShard(0);

        assertThat(router.forShard(200).id()).isEqualTo(1);
    }

    @Test
    void failsTheFirstLookupWhenNoLayoutCanBeLoaded() {
        rows.set(() -> List.of(CELL_0, new CellRange(1, 128, 255, "cell-7", CellStatus.ACTIVE)));

        assertThatThrownBy(() -> router.forShard(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cell-7");
    }

    @Test
    void validatesItsArguments() {
        assertThatThrownBy(() -> new CellRouter(List::of, Map.of(), REFRESH, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CellRouter(List::of, databases, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Cell(CELL_0, cell1)).isInstanceOf(IllegalArgumentException.class);
    }
}
