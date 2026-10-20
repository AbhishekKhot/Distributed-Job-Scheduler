package dev.kairos.persistence.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.persistence.PersistenceFixtures;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class FanOutQueryTest {

    private static final Comparator<Integer> ASCENDING = Comparator.naturalOrder();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CellRouter router = new CellRouter(
            () -> List.of(
                    new CellRange(0, 0, 127, "cell-0", CellStatus.ACTIVE),
                    new CellRange(1, 128, 255, "cell-1", CellStatus.ACTIVE)),
            Map.of(
                    "cell-0", PersistenceFixtures.unconnected("cell-0", meters),
                    "cell-1", PersistenceFixtures.unconnected("cell-1", meters)),
            Duration.ofSeconds(30),
            Clock.systemUTC());
    private final FanOutQuery fanOut = new FanOutQuery(router, Duration.ofSeconds(5));

    // Cell 0 holds the even numbers, cell 1 the odd ones; the list is ascending.
    private final Map<Integer, List<Integer>> rowsByCell = Map.of(
            0, List.of(0, 2, 4, 6, 8, 10),
            1, List.of(1, 3, 5, 7));

    private final CellPageQuery<Integer, Integer> query = (cell, after, limit) -> rowsByCell.get(cell.id()).stream()
            .filter(row -> after == null || row > after)
            .limit(limit)
            .toList();

    @Test
    void mergesBothCellsInOrder() {
        FanOutPage<Integer, Integer> page = fanOut.list(null, 5, query, ASCENDING, Function.identity());

        assertThat(page.items()).containsExactly(0, 1, 2, 3, 4);
        assertThat(page.next()).isEqualTo(4);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextKey()).contains(4);
    }

    @Test
    void pagesThroughEveryRowExactlyOnce() {
        List<Integer> seen = new ArrayList<>();
        @Nullable Integer after = null;
        int pages = 0;
        do {
            FanOutPage<Integer, Integer> page = fanOut.list(after, 3, query, ASCENDING, Function.identity());
            seen.addAll(page.items());
            after = page.next();
            pages++;
        } while (after != null);

        assertThat(seen).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 10);
        assertThat(pages).isEqualTo(4);
    }

    @Test
    void lastPageHasNoNextKey() {
        FanOutPage<Integer, Integer> page = fanOut.list(7, 10, query, ASCENDING, Function.identity());

        assertThat(page.items()).containsExactly(8, 10);
        assertThat(page.next()).isNull();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextKey()).isEmpty();
    }

    @Test
    void anExactlyFullLastPageHasNoNextKey() {
        FanOutPage<Integer, Integer> page = fanOut.list(5, 4, query, ASCENDING, Function.identity());

        assertThat(page.items()).containsExactly(6, 7, 8, 10);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void emptyCellsGiveAnEmptyPage() {
        FanOutPage<Integer, Integer> page =
                fanOut.list(null, 50, (cell, after, limit) -> List.of(), ASCENDING, Function.identity());

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void asksEachCellForOneRowMoreThanThePage() {
        List<Integer> limits = new CopyOnWriteArrayList<>();

        fanOut.list(
                null,
                7,
                (cell, after, limit) -> {
                    limits.add(limit);
                    return List.of();
                },
                ASCENDING,
                Function.identity());

        assertThat(limits).containsExactly(8, 8);
    }

    @Test
    void rejectsCellsThatReturnRowsOutOfOrderOrTooMany() {
        CellPageQuery<Integer, Integer> unordered = (cell, after, limit) -> List.of(3, 1);
        CellPageQuery<Integer, Integer> tooMany = (cell, after, limit) -> List.of(1, 2, 3);

        assertThatThrownBy(() -> fanOut.list(null, 5, unordered, ASCENDING, Function.identity()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order");
        assertThatThrownBy(() -> fanOut.list(null, 1, tooMany, ASCENDING, Function.identity()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailingCellFailsTheWholeRead() {
        CellPageQuery<Integer, Integer> failing = (cell, after, limit) -> {
            if (cell.id() == 1) {
                throw new IllegalStateException("cell 1 down");
            }
            return List.of(0);
        };

        assertThatThrownBy(() -> fanOut.list(null, 5, failing, ASCENDING, Function.identity()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cell 1 down");
    }

    @Test
    void aCellThatMissesTheDeadlineFailsTheWholeRead() {
        CountDownLatch release = new CountDownLatch(1);
        FanOutQuery impatient = new FanOutQuery(router, Duration.ofMillis(50));
        CellPageQuery<Integer, Integer> stuck = (cell, after, limit) -> {
            if (cell.id() == 1) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return List.of();
        };

        try {
            assertThatThrownBy(() -> impatient.list(null, 5, stuck, ASCENDING, Function.identity()))
                    .isInstanceOf(FanOutException.class)
                    .hasMessageContaining("Cell 1");
        } finally {
            release.countDown();
        }
    }

    @Test
    void validatesItsArguments() {
        assertThatThrownBy(() -> fanOut.list(null, 0, query, ASCENDING, Function.identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fanOut.list(null, FanOutQuery.MAX_LIMIT + 1, query, ASCENDING, Function.identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FanOutQuery(router, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSingleCellIsReadOnTheCallingThread() {
        CellRouter single = new CellRouter(
                () -> List.of(new CellRange(0, 0, 255, "cell-0", CellStatus.ACTIVE)),
                Map.of("cell-0", PersistenceFixtures.unconnected(Database.cellName(0), meters)),
                Duration.ofSeconds(30),
                Clock.systemUTC());
        Thread caller = Thread.currentThread();

        FanOutPage<Integer, Integer> page = new FanOutQuery(single, Duration.ofSeconds(1))
                .list(
                        null,
                        2,
                        (cell, after, limit) -> {
                            assertThat(Thread.currentThread()).isSameAs(caller);
                            return List.of(1, 2, 3);
                        },
                        ASCENDING,
                        Function.identity());

        assertThat(page.items()).containsExactly(1, 2);
        assertThat(page.next()).isEqualTo(2);
    }
}
