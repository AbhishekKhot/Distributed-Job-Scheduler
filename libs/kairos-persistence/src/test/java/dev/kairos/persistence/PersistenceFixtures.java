package dev.kairos.persistence;

import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.RepositoryFiles;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Test fixtures: databases on the shared Postgres container, with the real control and cell schemas, and databases
 * that are never connected for unit tests.
 *
 * <p>The schemas come from the migration scripts in {@code jobs/kairos-migrations}, so the tests exercise the same
 * tables and constraint names as production. Only the base scripts are applied (no partitions); the tests write to
 * unpartitioned tables only. {@code cell_map} gets the local layout: cell 0 holds shards 0–127, cell 1 holds 128–255.
 */
public final class PersistenceFixtures {

    private static final String CONTROL_SCHEMA =
            "jobs/kairos-migrations/src/main/resources/db/control/V2026_10_20_0900__create_control_schema.sql";
    private static final String CELL_SCHEMA =
            "jobs/kairos-migrations/src/main/resources/db/cell/V2026_10_20_0910__create_cell_schema.sql";
    private static final String SEED_CELL = """
            INSERT INTO scheduler.cell_map (cell_id, shard_from, shard_to, datasource_ref, status)
            VALUES (:cellId, :shardFrom, :shardTo, :ref, 'ACTIVE')
            ON CONFLICT (cell_id) DO NOTHING
            """;

    /** Inserts a minimal job row; the team and name pair is unique among non-deleted jobs. */
    public static final String INSERT_JOB = """
            INSERT INTO scheduler.jobs (job_id, team_id, name, version, endpoint_id, spec, created_by, created_at,
                updated_at)
            VALUES (:jobId, :teamId, :name, 1, :endpointId, CAST('{}' AS jsonb), 'user:test', :createdAt, :createdAt)
            """;

    private static boolean schemaReady;

    private PersistenceFixtures() {}

    /**
     * Applies the schemas and seeds {@code cell_map} once per test JVM.
     *
     * @param postgres the shared container
     */
    public static synchronized void ensureSchema(KairosPostgres postgres) {
        if (schemaReady) {
            return;
        }
        populate(postgres.controlOwnerDataSource(), CONTROL_SCHEMA);
        JdbcClient control = JdbcClient.create(postgres.controlDataSource());
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            populate(postgres.cellOwnerDataSource(cell), CELL_SCHEMA);
            control.sql(SEED_CELL)
                    .param("cellId", (short) cell)
                    .param("shardFrom", (short) (cell * 128))
                    .param("shardTo", (short) (cell * 128 + 127))
                    .param("ref", Database.cellName(cell))
                    .update();
        }
        schemaReady = true;
    }

    /**
     * Returns a registry over the container's shared pools, connecting as {@code sched_app}.
     *
     * @param postgres the shared container
     * @param options options of every database
     * @param meterRegistry where metrics go
     * @return a registry that owns no pool
     */
    public static DataSourceRegistry registry(
            KairosPostgres postgres, DatabaseOptions options, MeterRegistry meterRegistry) {
        ensureSchema(postgres);
        Database control = Database.of(Database.CONTROL, postgres.controlDataSource(), options, meterRegistry);
        List<Database> cells = new ArrayList<>();
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            cells.add(Database.of(Database.cellName(cell), postgres.cellDataSource(cell), options, meterRegistry));
        }
        return DataSourceRegistry.of(control, cells);
    }

    /**
     * Returns a database whose data source is never connected, for routing tests.
     *
     * @param name the database name
     * @param meterRegistry where metrics go
     * @return the database
     */
    public static Database unconnected(String name, MeterRegistry meterRegistry) {
        DataSource never = new DriverManagerDataSource("jdbc:postgresql://unused.invalid:5432/unused");
        return Database.of(name, never, DatabaseOptions.defaults(), meterRegistry);
    }

    /**
     * Inserts a job through a client, inside whatever transaction the client is part of.
     *
     * @param jdbc the client of the job's cell
     * @param jobId the job id
     * @param teamId the owning team
     * @param name the job name
     * @param createdAt the creation time
     * @return the number of inserted rows
     */
    public static int insertJob(JdbcClient jdbc, UUID jobId, UUID teamId, String name, Instant createdAt) {
        return jdbc.sql(INSERT_JOB)
                .param("jobId", jobId)
                .param("teamId", teamId)
                .param("name", name)
                .param("endpointId", TestIds.endpointId().value())
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
    }

    private static void populate(DataSource owner, String script) {
        new ResourceDatabasePopulator(new FileSystemResource(RepositoryFiles.resolve(script))).execute(owner);
    }
}
