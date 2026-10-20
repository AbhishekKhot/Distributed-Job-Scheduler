package dev.kairos.migrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.migrations.schema.CellMapSeeder;
import dev.kairos.migrations.schema.CellMapSeeder.CellRange;
import dev.kairos.migrations.schema.GrantVerifier;
import dev.kairos.migrations.schema.MigrationRunner;
import dev.kairos.migrations.schema.MigrationTarget;
import dev.kairos.migrations.schema.SchemaMigrator;
import dev.kairos.testsupport.KairosPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the job against the Postgres test container, laid out like the local stack (control database plus two cells),
 * and checks the resulting schema, partitions, grants and {@code cell_map}.
 *
 * <p>The application context starts the {@link MigrationRunner}, exactly as in production; the tests then run it a
 * second time and inspect the databases. The clock is fixed late on a UTC day, so the partition days do not depend on
 * when the test runs.
 */
@SpringBootTest(properties = "management.server.port=0")
class MigrationsIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-20T23:30:00Z");

    private static final String COLUMNS = """
            SELECT c.table_name || '|' || c.column_name || '|' || c.data_type || '|' || c.is_nullable
              FROM information_schema.columns c
              JOIN pg_catalog.pg_namespace n ON n.nspname = c.table_schema
              JOIN pg_catalog.pg_class t ON t.relnamespace = n.oid AND t.relname = c.table_name
             WHERE c.table_schema = 'scheduler'
               AND NOT t.relispartition
               AND t.relkind IN ('r', 'p')
               AND c.table_name <> 'flyway_schema_history'
             ORDER BY c.table_name, c.ordinal_position
            """;

    private static final String CHILD_PARTITIONS = """
            SELECT child.relname
              FROM pg_catalog.pg_inherits i
              JOIN pg_catalog.pg_class parent ON parent.oid = i.inhparent
              JOIN pg_catalog.pg_class child ON child.oid = i.inhrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = parent.relnamespace
             WHERE n.nspname = 'scheduler' AND parent.relname = :parent
             ORDER BY child.relname
            """;

    private static final String INSERT_RUN = """
            INSERT INTO scheduler.run_inflight (shard_id, run_id, team_id, target_type, definition_id,
                definition_version, target_id, endpoint_id, endpoint_version, scheduled_at, due_at, state,
                series_started_at, priority, trigger)
            VALUES (:shard, :runId, :teamId, 1, :jobId, 1, :jobId, :endpointId, 1, :scheduledAt, :scheduledAt, 1,
                :scheduledAt, 5, 1)
            """;

    private static final String INSERT_DEAD_LETTER_PREFIX = """
            INSERT INTO scheduler.dead_letters (dlq_id, team_id, run_id, shard_id, scheduled_at, target_type,
                definition_id, definition_version, target_id, endpoint_id, endpoint_version, attempt, infra_attempt,
                priority, reason, created_at""";

    private static final String DEAD_LETTER_VALUES = """
            VALUES (:dlqId, :teamId, :runId, 200, :at, 1, :jobId, 1, :jobId, :endpointId, 1, 6, 0, 5, 1, :at""";

    private static final String INSERT_DEAD_LETTER_WITH_FENCE =
            INSERT_DEAD_LETTER_PREFIX + ", fence) " + DEAD_LETTER_VALUES + ", :fence)";

    private static final String INSERT_DEAD_LETTER_WITHOUT_FENCE =
            INSERT_DEAD_LETTER_PREFIX + ") " + DEAD_LETTER_VALUES + ")";

    @Autowired
    private MigrationRunner runner;

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        KairosPostgres postgres = KairosPostgres.get();
        registry.add("kairos.db.username", postgres::ownerUsername);
        registry.add("kairos.db.password", postgres::ownerPassword);
        registry.add("kairos.control.db.url", postgres::controlJdbcUrl);
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            int number = cell;
            registry.add("kairos.cell[" + cell + "].db.url", () -> postgres.cellJdbcUrl(number));
        }
    }

    @Test
    void controlSchemaMatchesTheDocumentedTables() throws IOException {
        assertThat(columns(KairosPostgres.get().controlOwnerDataSource()))
                .containsExactlyElementsOf(expected("expected-schema/control.txt"));
    }

    @Test
    void everyCellSchemaMatchesTheDocumentedTables() throws IOException {
        List<String> expected = expected("expected-schema/cell.txt");
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            assertThat(columns(KairosPostgres.get().cellOwnerDataSource(cell)))
                    .as("cell %d", cell)
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void secondRunAppliesNothingAndChangesNothing() {
        runner.run(new DefaultApplicationArguments());

        SchemaMigrator migrator = new SchemaMigrator(KairosPostgres.APP_USERNAME);
        assertThat(migrator.migrate(runner.control())).isZero();
        for (MigrationTarget cell : runner.cells()) {
            assertThat(migrator.migrate(cell)).isZero();
        }
        assertThat(cellMap()).containsExactly(new CellRange(0, 0, 127, "cell-0"), new CellRange(1, 128, 255, "cell-1"));
    }

    @Test
    void dailyPartitionsCoverTodayAndThreeDaysAheadInUtc() {
        JdbcClient cell = JdbcClient.create(KairosPostgres.get().cellOwnerDataSource(0));
        List<String> days = List.of("_p20261020", "_p20261021", "_p20261022", "_p20261023", "_default");
        for (String table : List.of("run_history", "task_outputs")) {
            assertThat(children(cell, table))
                    .containsExactlyInAnyOrderElementsOf(
                            days.stream().map(day -> table + day).toList());
        }
        JdbcClient control = JdbcClient.create(KairosPostgres.get().controlOwnerDataSource());
        assertThat(children(control, "audit_log"))
                .containsExactlyInAnyOrderElementsOf(
                        days.stream().map(day -> "audit_log" + day).toList());
    }

    @Test
    void hotTablesHaveSixteenHashPartitionsWithTunedAutovacuum() {
        JdbcClient cell = JdbcClient.create(KairosPostgres.get().cellOwnerDataSource(1));
        for (String table : List.of("run_inflight", "outbox")) {
            assertThat(children(cell, table)).hasSize(16).allMatch(name -> name.startsWith(table + "_h"));
        }
        String options =
                cell.sql("""
                        SELECT array_to_string(c.reloptions, ',')
                          FROM pg_catalog.pg_class c
                          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'scheduler' AND c.relname = :table
                        """).param("table", "outbox_h07").query(String.class).single();
        assertThat(options).contains("autovacuum_vacuum_scale_factor=0.01", "autovacuum_vacuum_cost_limit=2000");
    }

    @Test
    void appRoleHasDmlButCannotCreatePartitionsOrReadMigrationHistory() {
        JdbcClient app = JdbcClient.create(KairosPostgres.get().cellDataSource(0));
        UUID jobId = UUID.randomUUID();
        app.sql("""
                        INSERT INTO scheduler.jobs (job_id, team_id, name, version, endpoint_id, spec, created_by,
                            created_at, updated_at)
                        VALUES (:jobId, :teamId, :name, 1, :endpointId, '{}'::jsonb, 'user:abc', now(), now())
                        """)
                .param("jobId", jobId)
                .param("teamId", UUID.randomUUID())
                .param("name", "grant-check-" + jobId)
                .param("endpointId", UUID.randomUUID())
                .update();
        assertThat(app.sql("DELETE FROM scheduler.jobs WHERE job_id = :jobId")
                        .param("jobId", jobId)
                        .update())
                .isOne();

        assertThatThrownBy(() -> app.sql("SELECT scheduler.create_day_partition('run_history', DATE '2026-12-01')")
                        .query(String.class)
                        .single())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.sql("SELECT count(*) FROM scheduler.flyway_schema_history")
                        .query(Long.class)
                        .single())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void oneRunPerOccurrence() {
        JdbcClient app = JdbcClient.create(KairosPostgres.get().cellDataSource(1));
        UUID jobId = UUID.randomUUID();
        Instant scheduledAt = Instant.parse("2026-10-20T10:00:00Z");
        insertRun(app, jobId, scheduledAt);
        try {
            assertThatThrownBy(() -> insertRun(app, jobId, scheduledAt)).isInstanceOf(DuplicateKeyException.class);
        } finally {
            app.sql("DELETE FROM scheduler.run_inflight WHERE target_id = :jobId")
                    .param("jobId", jobId)
                    .update();
        }
    }

    @Test
    void deadLetterKeepsTheFenceTheRunHadAndRequiresIt() {
        JdbcClient app = JdbcClient.create(KairosPostgres.get().cellDataSource(1));
        UUID jobId = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        try {
            insertDeadLetter(app, INSERT_DEAD_LETTER_WITH_FENCE, kept, jobId, 7);
            assertThat(app.sql("SELECT fence FROM scheduler.dead_letters WHERE dlq_id = :dlqId")
                            .param("dlqId", kept)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(7);

            assertThatThrownBy(() ->
                            insertDeadLetter(app, INSERT_DEAD_LETTER_WITHOUT_FENCE, UUID.randomUUID(), jobId, null))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("fence");
            assertThatThrownBy(() -> insertDeadLetter(app, INSERT_DEAD_LETTER_WITH_FENCE, UUID.randomUUID(), jobId, -1))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("dead_letters_fence_ck");
        } finally {
            app.sql("DELETE FROM scheduler.dead_letters WHERE target_id = :jobId")
                    .param("jobId", jobId)
                    .update();
        }
    }

    @Test
    void partitionFunctionRejectsTablesOutsideItsAllowList() {
        JdbcClient owner = JdbcClient.create(KairosPostgres.get().cellOwnerDataSource(0));
        assertThatThrownBy(() -> owner.sql("SELECT scheduler.create_day_partition(:table, DATE '2026-12-01')")
                        .param("table", "jobs")
                        .query(String.class)
                        .single())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("not a day-partitioned table");
    }

    @Test
    void grantVerificationFailsWhenTheAppRoleLacksDml() {
        MigrationTarget cell = runner.cells().get(0);
        JdbcClient owner = JdbcClient.create(cell.dataSource());
        owner.sql("CREATE TABLE scheduler.grant_probe (id integer)").update();
        try {
            owner.sql("REVOKE DELETE ON scheduler.grant_probe FROM sched_app").update();
            assertThatThrownBy(() -> new GrantVerifier(KairosPostgres.APP_USERNAME).verify(cell))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("grant_probe")
                    .hasMessageContaining("cell 0");
        } finally {
            owner.sql("DROP TABLE scheduler.grant_probe").update();
        }
    }

    @Test
    void cellMapThatDisagreesWithTheConfigurationFailsWithoutWriting() {
        CellMapSeeder seeder = new CellMapSeeder();
        MigrationTarget control = runner.control();

        assertThatThrownBy(() -> seeder.seed(control, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operator command");
        assertThatThrownBy(() -> seeder.seed(control, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(cellMap()).hasSize(2);
    }

    private static void insertRun(JdbcClient app, UUID jobId, Instant scheduledAt) {
        app.sql(INSERT_RUN)
                .param("shard", (short) 200)
                .param("runId", UUID.randomUUID())
                .param("teamId", UUID.randomUUID())
                .param("jobId", jobId)
                .param("endpointId", UUID.randomUUID())
                .param("scheduledAt", scheduledAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static void insertDeadLetter(JdbcClient app, String sql, UUID dlqId, UUID jobId, Integer fence) {
        JdbcClient.StatementSpec statement = app.sql(sql)
                .param("dlqId", dlqId)
                .param("teamId", UUID.randomUUID())
                .param("runId", UUID.randomUUID())
                .param("at", Instant.parse("2026-10-20T10:00:00Z").atOffset(ZoneOffset.UTC))
                .param("jobId", jobId)
                .param("endpointId", UUID.randomUUID());
        if (fence != null) {
            statement = statement.param("fence", fence);
        }
        statement.update();
    }

    private static List<String> columns(DataSource dataSource) {
        return JdbcClient.create(dataSource).sql(COLUMNS).query(String.class).list();
    }

    private static List<String> children(JdbcClient jdbc, String parent) {
        return jdbc.sql(CHILD_PARTITIONS)
                .param("parent", parent)
                .query(String.class)
                .list();
    }

    private static List<CellRange> cellMap() {
        return JdbcClient.create(KairosPostgres.get().controlDataSource())
                .sql("SELECT cell_id, shard_from, shard_to, datasource_ref FROM scheduler.cell_map ORDER BY cell_id")
                .query((row, rowNumber) -> new CellRange(
                        row.getInt("cell_id"),
                        row.getInt("shard_from"),
                        row.getInt("shard_to"),
                        row.getString("datasource_ref")))
                .list();
    }

    private static List<String> expected(String resource) throws IOException {
        try (InputStream in = MigrationsIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
        }
    }

    /** Fixes the clock the job uses to pick partition days. */
    @TestConfiguration
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
