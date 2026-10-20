package dev.kairos.engine;

import com.google.protobuf.ByteString;
import dev.kairos.domain.id.JobId;
import dev.kairos.domain.id.WorkflowId;
import dev.kairos.domain.id.WorkflowRunId;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.engine.run.RunTarget;
import dev.kairos.persistence.cell.CellMapSource;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.RepositoryFiles;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test fixtures: cells on the shared Postgres container with the real schema, and run targets.
 *
 * <p>The schema comes from the migration scripts in {@code jobs/kairos-migrations}, including the hash partitions of
 * {@code run_inflight} and {@code outbox}, so the tests exercise the same tables, partitions and constraint names as
 * production. The scripts run as the schema owner in one simple-protocol call each (the driver understands the
 * dollar-quoted blocks); the Flyway placeholder for the application role is filled in first. {@code cell_map} gets the
 * local layout: cell 0 holds shards 0–127, cell 1 holds 128–255.
 */
public final class EngineFixtures {

    /** A secret reference the test requests carry; its value never appears in a message. */
    public static final String SECRET_REF = "billing-token";

    private static final String MIGRATIONS = "jobs/kairos-migrations/src/main/resources/db/";
    private static final String CONTROL_SCHEMA = MIGRATIONS + "control/V2026_10_20_0900__create_control_schema.sql";
    private static final List<String> CELL_SCRIPTS = List.of(
            MIGRATIONS + "cell/V2026_10_20_0910__create_cell_schema.sql",
            MIGRATIONS + "cell/V2026_10_20_0915__partition_cell_tables.sql");
    private static final String APP_ROLE_PLACEHOLDER = "${appRole}";
    private static final String SEED_CELL = """
            INSERT INTO scheduler.cell_map (cell_id, shard_from, shard_to, datasource_ref, status)
            VALUES (:cellId, :shardFrom, :shardTo, :ref, 'ACTIVE')
            ON CONFLICT (cell_id) DO NOTHING
            """;

    private static boolean schemaReady;

    private EngineFixtures() {}

    /**
     * Applies the schemas and seeds {@code cell_map} once per test JVM.
     *
     * @param postgres the shared container
     */
    public static synchronized void ensureSchema(KairosPostgres postgres) {
        if (schemaReady) {
            return;
        }
        apply(postgres.controlOwnerDataSource(), CONTROL_SCHEMA, postgres.appUsername());
        JdbcClient control = JdbcClient.create(postgres.controlDataSource());
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            for (String script : CELL_SCRIPTS) {
                apply(postgres.cellOwnerDataSource(cell), script, postgres.appUsername());
            }
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
     * @return a registry that owns no pool
     */
    public static DataSourceRegistry registry(KairosPostgres postgres) {
        ensureSchema(postgres);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DatabaseOptions options = DatabaseOptions.defaults();
        Database control = Database.of(Database.CONTROL, postgres.controlDataSource(), options, meters);
        List<Database> cells = new ArrayList<>();
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            cells.add(Database.of(Database.cellName(cell), postgres.cellDataSource(cell), options, meters));
        }
        return DataSourceRegistry.of(control, cells);
    }

    /**
     * Returns a router over the registry's cells.
     *
     * @param registry the databases
     * @return the router
     */
    public static CellRouter router(DataSourceRegistry registry) {
        return new CellRouter(
                CellMapSource.fromControl(registry.control()),
                registry.cells(),
                CellRouter.DEFAULT_REFRESH_INTERVAL,
                Clock.systemUTC());
    }

    /**
     * Returns the call every test run makes: a path parameter, a header, a JSON body and one secret reference.
     *
     * @return the call
     */
    public static CallSpec call() {
        return new CallSpec(
                30_000,
                RequestSnapshot.newBuilder()
                        .putPathParams("orderId", "42")
                        .addQuery(NameValue.newBuilder().setName("region").setValue("eu"))
                        .addHeaders(NameValue.newBuilder()
                                .setName("X-Request-Source")
                                .setValue("kairos"))
                        .setBody(ByteString.copyFromUtf8("{\"amount\":10}"))
                        .addSecretRefs(SECRET_REF)
                        .build());
    }

    /**
     * Returns a new job in a shard.
     *
     * @param shard the job's shard
     * @param priority the job's priority
     * @return the target
     */
    public static RunTarget job(int shard, int priority) {
        JobId job = TestIds.jobId(shard);
        return new RunTarget(
                TestIds.teamId(),
                TargetType.JOB,
                job.value(),
                3,
                job.value(),
                null,
                TestIds.endpointId(),
                2,
                Priority.of(priority),
                null,
                call());
    }

    /**
     * Returns a task of a new workflow run in a shard.
     *
     * @param shard the workflow's shard
     * @param taskKey the task key
     * @return the target
     */
    public static RunTarget task(int shard, String taskKey) {
        WorkflowId workflow = TestIds.workflowId(shard);
        WorkflowRunId workflowRun = TestIds.workflowRunId(shard);
        return new RunTarget(
                TestIds.teamId(),
                TargetType.WORKFLOW_TASK,
                workflow.value(),
                1,
                workflowRun.value(),
                TaskKey.of(taskKey),
                TestIds.endpointId(),
                1,
                Priority.of(5),
                null,
                call());
    }

    private static void apply(DataSource owner, String script, String appRole) {
        String sql;
        try {
            sql = Files.readString(RepositoryFiles.resolve(script), StandardCharsets.UTF_8)
                    .replace(APP_ROLE_PLACEHOLDER, appRole);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (Connection connection = owner.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not apply " + script, e);
        }
    }
}
