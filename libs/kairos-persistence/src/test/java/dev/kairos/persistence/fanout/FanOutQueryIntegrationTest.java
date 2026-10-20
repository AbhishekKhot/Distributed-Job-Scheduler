package dev.kairos.persistence.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.id.JobId;
import dev.kairos.persistence.PersistenceFixtures;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellMapSource;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.persistence.tx.CellTransactions;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Lists one team's jobs across both cells, newest first, page by page. */
@KairosIntegrationTest(kafka = false, redis = false)
class FanOutQueryIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-10-20T00:00:00Z");

    private static final String FIRST_PAGE = """
            SELECT job_id, created_at FROM scheduler.jobs
             WHERE team_id = :teamId
             ORDER BY created_at DESC, job_id DESC
             LIMIT :limit
            """;

    private static final String NEXT_PAGE = """
            SELECT job_id, created_at FROM scheduler.jobs
             WHERE team_id = :teamId AND (created_at, job_id) < (:createdAt, :jobId)
             ORDER BY created_at DESC, job_id DESC
             LIMIT :limit
            """;

    /** The newest-first order of the SQL: PostgreSQL compares uuids bytewise, like their lower-case text. */
    private static final Comparator<JobRow> NEWEST_FIRST = Comparator.comparing(JobRow::createdAt)
            .thenComparing(row -> row.jobId().toString())
            .reversed();

    private final UUID teamId = TestIds.teamId().value();
    private CellTransactions cells;
    private FanOutQuery fanOut;

    record JobRow(UUID jobId, Instant createdAt) {}

    @BeforeEach
    void setUp(KairosPostgres postgres) {
        DataSourceRegistry registry =
                PersistenceFixtures.registry(postgres, DatabaseOptions.defaults(), new SimpleMeterRegistry());
        CellRouter router = new CellRouter(
                CellMapSource.fromControl(registry.control()),
                registry.cells(),
                CellRouter.DEFAULT_REFRESH_INTERVAL,
                Clock.systemUTC());
        cells = new CellTransactions(router);
        fanOut = new FanOutQuery(router, Duration.ofSeconds(5));
    }

    @Test
    void mergesBothCellsInOrderWithAStableCursor() {
        List<JobRow> inserted = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            // Alternate cells; two rows share a timestamp so the id breaks the tie.
            int shard = i % 2 == 0 ? 3 + i : 200 + i;
            Instant createdAt = BASE.plusSeconds(i == 10 ? 9 : i);
            inserted.add(insert(TestIds.jobId(shard), "fan-out-" + i, createdAt));
        }
        List<JobRow> expected = inserted.stream().sorted(NEWEST_FIRST).toList();

        List<JobRow> seen = new ArrayList<>();
        @Nullable JobRow after = null;
        int pages = 0;
        do {
            FanOutPage<JobRow, JobRow> page = fanOut.list(after, 4, this::fetch, NEWEST_FIRST, row -> row);
            seen.addAll(page.items());
            after = page.next();
            pages++;
            if (pages == 1) {
                // A row created after the first page sorts before the cursor and must not appear later.
                insert(TestIds.jobId(77), "fan-out-late", BASE.plusSeconds(60));
            }
        } while (after != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
    }

    private List<JobRow> fetch(Cell cell, @Nullable JobRow after, int limit) {
        var statement = after == null
                ? cell.jdbc().sql(FIRST_PAGE)
                : cell.jdbc()
                        .sql(NEXT_PAGE)
                        .param("createdAt", OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC))
                        .param("jobId", after.jobId());
        return statement
                .param("teamId", teamId)
                .param("limit", limit)
                .query((row, rowNumber) -> new JobRow(
                        row.getObject("job_id", UUID.class),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private JobRow insert(JobId jobId, String name, Instant createdAt) {
        cells.execute(
                jobId.shard(), jdbc -> PersistenceFixtures.insertJob(jdbc, jobId.value(), teamId, name, createdAt));
        return new JobRow(jobId.value(), createdAt);
    }
}
