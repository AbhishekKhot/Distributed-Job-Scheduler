package dev.kairos.persistence.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.id.JobId;
import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.PersistenceFixtures;
import dev.kairos.persistence.cell.CellMapSource;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.error.ConstraintConflicts;
import dev.kairos.persistence.error.RetryableDatabaseException;
import dev.kairos.persistence.error.SqlErrorTranslator;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.persistence.jdbc.DatabaseTransactions;
import dev.kairos.persistence.jdbc.TransactionSettings;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;

/**
 * Transactions on real databases: commit and rollback, error translation with the real constraint names, retries of
 * serialization failures, deadlines, the one-database rule, batches and metrics.
 */
@KairosIntegrationTest(kafka = false, redis = false)
class TransactionsIntegrationTest {

    private static final Instant CREATED = Instant.parse("2026-10-20T10:00:00Z");
    private static final ConstraintConflicts JOB_NAMES = ConstraintConflicts.of(
            "jobs_team_name_uk", ErrorCode.JOB_NAME_TAKEN, "A job with this name already exists.");
    private static final String COUNT_TEAM_JOBS = "SELECT count(*) FROM scheduler.jobs WHERE team_id = :teamId";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID teamId = TestIds.teamId().value();
    private DataSourceRegistry registry;
    private CellTransactions cells;
    private ControlTransactions control;

    @BeforeEach
    void setUp(KairosPostgres postgres) {
        DatabaseOptions options = new DatabaseOptions(
                0,
                Duration.ofSeconds(3),
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                2,
                TransactionSettings.defaults());
        registry = PersistenceFixtures.registry(postgres, options, meters);
        CellRouter router = new CellRouter(
                CellMapSource.fromControl(registry.control()),
                registry.cells(),
                CellRouter.DEFAULT_REFRESH_INTERVAL,
                Clock.systemUTC());
        cells = new CellTransactions(router);
        control = new ControlTransactions(registry.control());
    }

    @Test
    void commitsTheWorkOnTheShardsCell() {
        JobId jobId = TestIds.jobId(200);

        int inserted = cells.execute(jobId.shard(), jdbc -> insertJob(jdbc, jobId, "nightly-report"));

        assertThat(inserted).isEqualTo(1);
        assertThat(countJobs(registry.cell("cell-1").orElseThrow())).isEqualTo(1);
        assertThat(countJobs(registry.cell("cell-0").orElseThrow())).isZero();
    }

    @Test
    void rollsBackWhenTheWorkThrows() {
        JobId jobId = TestIds.jobId(10);

        assertThatThrownBy(() -> cells.execute(jobId.shard(), jdbc -> {
                    insertJob(jdbc, jobId, "rolled-back");
                    throw new IllegalStateException("validation failed after the insert");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countJobs(registry.cell("cell-0").orElseThrow())).isZero();
    }

    @Test
    void duplicateNameRaisesTheRegisteredConflict() {
        JobId first = TestIds.jobId(5);
        JobId second = TestIds.jobId(5);
        TransactionSettings settings = TransactionSettings.defaults().withConflicts(JOB_NAMES);
        cells.execute(first.shard(), settings, jdbc -> insertJob(jdbc, first, "duplicate-me"));

        assertThatThrownBy(
                        () -> cells.execute(second.shard(), settings, jdbc -> insertJob(jdbc, second, "duplicate-me")))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.errorCode()).isEqualTo(ErrorCode.JOB_NAME_TAKEN);
                    assertThat(conflict.detail()).isEqualTo("A job with this name already exists.");
                });
    }

    @Test
    void duplicateKeyOnAnUnregisteredConstraintRaisesTheGenericConflict() {
        JobId jobId = TestIds.jobId(140);
        cells.execute(jobId.shard(), jdbc -> insertJob(jdbc, jobId, "first-name"));

        assertThatThrownBy(() -> cells.execute(jobId.shard(), jdbc -> insertJob(jdbc, jobId, "second-name")))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        conflict -> assertThat(conflict.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void serializationFailureIsRetryableAndRetriedWithTheWholeTransaction() {
        JobId jobId = TestIds.jobId(42);
        Shard shard = jobId.shard();
        cells.execute(shard, jdbc -> insertJob(jdbc, jobId, "contended"));
        TransactionSettings repeatableRead = TransactionSettings.defaults().withIsolation(Isolation.REPEATABLE_READ);
        AtomicInteger attempts = new AtomicInteger();

        String committed = cells.executeWithRetry(shard, repeatableRead, jdbc -> {
            readVersion(jdbc, jobId);
            if (attempts.incrementAndGet() == 1) {
                // A concurrent transaction on another thread changes the row after this snapshot was taken.
                CompletableFuture.runAsync(() -> cells.execute(shard, other -> bumpVersion(other, jobId)))
                        .join();
            }
            bumpVersion(jdbc, jobId);
            return "committed";
        });

        assertThat(committed).isEqualTo("committed");
        assertThat(attempts).hasValue(2);
        Integer version = cells.execute(shard, jdbc -> readVersion(jdbc, jobId));
        assertThat(version).isEqualTo(3);
    }

    @Test
    void serializationFailureWithoutRetrySurfacesAsRetryable() {
        JobId jobId = TestIds.jobId(43);
        Shard shard = jobId.shard();
        cells.execute(shard, jdbc -> insertJob(jdbc, jobId, "contended-once"));
        TransactionSettings repeatableRead = TransactionSettings.defaults().withIsolation(Isolation.REPEATABLE_READ);

        assertThatThrownBy(() -> cells.execute(shard, repeatableRead, jdbc -> {
                    readVersion(jdbc, jobId);
                    CompletableFuture.runAsync(() -> cells.execute(shard, other -> bumpVersion(other, jobId)))
                            .join();
                    return bumpVersion(jdbc, jobId);
                }))
                .isInstanceOfSatisfying(
                        RetryableDatabaseException.class,
                        e -> assertThat(e.reason()).isEqualTo(RetryableDatabaseException.Reason.SERIALIZATION_FAILURE));
    }

    @Test
    void aStatementThatOutlivesTheDeadlineIsCancelled() {
        TransactionSettings oneSecond = TransactionSettings.defaults().withTimeout(Duration.ofSeconds(1));

        assertThatThrownBy(() -> control.execute(
                        oneSecond,
                        jdbc -> jdbc.sql("SELECT pg_sleep(5)").query().listOfRows()))
                // 57014: the driver cancelled the statement when the transaction's time ran out.
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        e -> assertThat(SqlErrorTranslator.sqlState(e)).isEqualTo("57014"));
    }

    @Test
    void aTransactionNeverSpansTwoDatabases() {
        JobId onCell0 = TestIds.jobId(1);
        JobId onCell1 = TestIds.jobId(129);

        assertThatThrownBy(() -> cells.execute(onCell0.shard(), jdbc -> {
                    insertJob(jdbc, onCell0, "outer");
                    return cells.execute(onCell1.shard(), inner -> insertJob(inner, onCell1, "inner"));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never spans two databases");
        assertThatThrownBy(() -> control.execute(jdbc -> cells.execute(onCell0.shard(), inner -> 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(countJobs(registry.cell("cell-0").orElseThrow())).isZero();
    }

    @Test
    void aNestedCallOnTheSameDatabaseJoinsTheTransaction() {
        JobId first = TestIds.jobId(60);
        JobId second = TestIds.jobId(61);

        assertThatThrownBy(() -> cells.execute(first.shard(), jdbc -> {
                    insertJob(jdbc, first, "joined-one");
                    cells.executeWithRetry(second.shard(), inner -> insertJob(inner, second, "joined-two"));
                    throw new IllegalStateException("roll both back");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countJobs(registry.cell("cell-0").orElseThrow())).isZero();
    }

    @Test
    void batchUpdateWritesEveryRowAcrossBatches() {
        Database cell1 = registry.cell("cell-1").orElseThrow();
        List<JobId> jobs = List.of(
                TestIds.jobId(130), TestIds.jobId(131), TestIds.jobId(132), TestIds.jobId(133), TestIds.jobId(134));

        int[] counts = cell1.transactions()
                .execute(jdbc -> cell1.batchUpdate(
                        PersistenceFixtures.INSERT_JOB,
                        jobs,
                        job -> new MapSqlParameterSource()
                                .addValue("jobId", job.value())
                                .addValue("teamId", teamId)
                                .addValue("name", "batch-" + job.shard().value())
                                .addValue("endpointId", TestIds.endpointId().value())
                                .addValue("createdAt", OffsetDateTime.parse("2026-10-20T10:00:00Z"))));

        assertThat(counts).containsExactly(1, 1, 1, 1, 1);
        assertThat(countJobs(cell1)).isEqualTo(5);
        assertThat(cell1.batchUpdate(
                        PersistenceFixtures.INSERT_JOB, List.<JobId>of(), job -> new MapSqlParameterSource()))
                .isEmpty();
    }

    @Test
    void updateReturningMapsTheChangedRows() {
        JobId jobId = TestIds.jobId(150);
        Database cell1 = registry.cell("cell-1").orElseThrow();
        cells.execute(jobId.shard(), jdbc -> insertJob(jdbc, jobId, "returning"));

        List<Integer> versions = cell1.updateReturning(
                "UPDATE scheduler.jobs SET version = version + 1 WHERE team_id = :teamId RETURNING version",
                new MapSqlParameterSource("teamId", teamId),
                (row, rowNumber) -> row.getInt("version"));

        assertThat(versions).containsExactly(2);
    }

    @Test
    void controlTransactionsRunOnTheControlDatabase() {
        String database = control.executeWithRetry(jdbc ->
                jdbc.sql("SELECT current_database()").query(String.class).single());
        Long cellRows = control.execute(
                TransactionSettings.defaults(),
                jdbc -> jdbc.sql("SELECT count(*) FROM scheduler.cell_map")
                        .query(Long.class)
                        .single());
        String retried = control.executeWithRetry(TransactionSettings.defaults(), jdbc -> "ok");

        assertThat(database).isEqualTo("kairos_control");
        assertThat(cellRows).isEqualTo(2L);
        assertThat(retried).isEqualTo("ok");
        assertThatThrownBy(() -> new ControlTransactions(registry.cell("cell-0").orElseThrow()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsTransactionDurationPerDatabase() {
        JobId jobId = TestIds.jobId(250);
        cells.execute(jobId.shard(), jdbc -> insertJob(jdbc, jobId, "timed"));
        control.execute(jdbc -> jdbc.sql("SELECT 1").query(Integer.class).single());

        Timer cellTimer = meters.find(DatabaseTransactions.TRANSACTION_TIMER)
                .tag("cell", "cell-1")
                .timer();
        Timer controlTimer = meters.find(DatabaseTransactions.TRANSACTION_TIMER)
                .tag("cell", "control")
                .timer();
        assertThat(cellTimer).isNotNull();
        assertThat(cellTimer.count()).isGreaterThanOrEqualTo(1);
        assertThat(controlTimer).isNotNull();
        assertThat(controlTimer.count()).isGreaterThanOrEqualTo(1);
        assertThat(registry.cell("cell-1").orElseThrow().transactions().defaults())
                .isEqualTo(TransactionSettings.defaults());
    }

    private int insertJob(JdbcClient jdbc, JobId jobId, String name) {
        return PersistenceFixtures.insertJob(jdbc, jobId.value(), teamId, name, CREATED);
    }

    private long countJobs(Database database) {
        return database.jdbc()
                .sql(COUNT_TEAM_JOBS)
                .param("teamId", teamId)
                .query(Long.class)
                .single();
    }

    private static int readVersion(JdbcClient jdbc, JobId jobId) {
        return jdbc.sql("SELECT version FROM scheduler.jobs WHERE job_id = :jobId")
                .param("jobId", jobId.value())
                .query(Integer.class)
                .single();
    }

    private static int bumpVersion(JdbcClient jdbc, JobId jobId) {
        return jdbc.sql("UPDATE scheduler.jobs SET version = version + 1 WHERE job_id = :jobId")
                .param("jobId", jobId.value())
                .update();
    }
}
