package dev.kairos.state.settle;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.retry.RetryPlanner;
import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellMapSource;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.ProtoTimestamps;
import dev.kairos.proto.v1.Result;
import dev.kairos.state.settle.application.ResultDecider;
import dev.kairos.state.settle.application.SettlementBatch;
import dev.kairos.state.settle.application.WorkflowResultHandler;
import dev.kairos.state.settle.infrastructure.JdbcResultSettler;
import dev.kairos.state.settle.infrastructure.SettlementPolicyLoader;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.RepositoryFiles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

final class SettlementFixtures {
    private static boolean ready;
    final MutableClock clock = MutableClock.at(Instant.parse("2026-10-25T10:00:00Z"));
    final KairosIdFactory ids = new KairosIdFactory(clock, new SecureRandom());
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final CellRouter cells;
    final JdbcClient control;
    final List<SettlementBatch.Settled> observed = java.util.Collections.synchronizedList(new ArrayList<>());
    final UUID team = ids.newTeamId().value();
    final UUID endpoint = ids.newEndpointId().value();

    SettlementFixtures(KairosPostgres postgres) {
        ensureSchema(postgres);
        Database controlDb =
                Database.of(Database.CONTROL, postgres.controlDataSource(), DatabaseOptions.defaults(), meters);
        List<Database> databases = new ArrayList<>();
        for (int i = 0; i < 2; i++)
            databases.add(
                    Database.of(Database.cellName(i), postgres.cellDataSource(i), DatabaseOptions.defaults(), meters));
        DataSourceRegistry registry = DataSourceRegistry.of(controlDb, databases);
        cells = new CellRouter(
                CellMapSource.fromControl(controlDb), registry.cells(), CellRouter.DEFAULT_REFRESH_INTERVAL, clock);
        control = controlDb.jdbc();
        control.sql("INSERT INTO scheduler.teams(team_id,slug,created_at,updated_at) VALUES (:id,:slug,:now,:now)")
                .param("id", team)
                .param("slug", "test-" + team)
                .param("now", now())
                .update();
        control.sql(
                        "INSERT INTO scheduler.api_endpoints(endpoint_id,team_id,name,version,spec,created_at,updated_at) VALUES (:id,:team,'target',1,'{}',:now,:now)")
                .param("id", endpoint)
                .param("team", team)
                .param("now", now())
                .update();
        endpointSpec("{}");
    }

    static synchronized void ensureSchema(KairosPostgres postgres) {
        if (ready) return;
        apply(postgres.controlOwnerDataSource(), "control", postgres.appUsername());
        JdbcClient control = JdbcClient.create(postgres.controlDataSource());
        for (int i = 0; i < 2; i++) {
            apply(postgres.cellOwnerDataSource(i), "cell", postgres.appUsername());
            control.sql(
                            "INSERT INTO scheduler.cell_map(cell_id,shard_from,shard_to,datasource_ref,status) VALUES (:id,:lo,:hi,:ref,'ACTIVE')")
                    .param("id", i)
                    .param("lo", i * 128)
                    .param("hi", i * 128 + 127)
                    .param("ref", Database.cellName(i))
                    .update();
        }
        ready = true;
    }

    private static void apply(DataSource owner, String directory, String role) {
        try (var paths = Files.list(RepositoryFiles.resolve(
                                "jobs/kairos-migrations/src/main/resources/db/" + directory + "/afterMigrate.sql")
                        .getParent());
                var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            for (var path : paths.filter(p -> p.getFileName().toString().startsWith("V")
                            && p.toString().endsWith(".sql"))
                    .sorted()
                    .toList()) statement.execute(Files.readString(path).replace("${appRole}", role));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void endpointSpec(String spec) {
        control.sql(
                        "INSERT INTO scheduler.api_endpoint_versions(endpoint_id,version,spec,created_at,created_by) VALUES (:id,1,CAST(:spec AS jsonb),:now,'user:test') ON CONFLICT(endpoint_id,version) DO UPDATE SET spec=EXCLUDED.spec")
                .param("id", endpoint)
                .param("spec", spec)
                .param("now", now())
                .update();
    }

    UUID job(int shard, String spec) {
        UUID job = ids.newJobId(Shard.of(shard)).value();
        JdbcClient jdbc = cells.forShard(shard).jdbc();
        jdbc.sql(
                        "INSERT INTO scheduler.jobs(job_id,team_id,name,version,endpoint_id,spec,created_by,created_at,updated_at) VALUES (:id,:team,:name,1,:endpoint,CAST(:spec AS jsonb),'user:test',:now,:now)")
                .param("id", job)
                .param("team", team)
                .param("name", job.toString())
                .param("endpoint", endpoint)
                .param("spec", spec)
                .param("now", now())
                .update();
        jdbc.sql(
                        "INSERT INTO scheduler.job_versions(job_id,version,spec,created_at) SELECT job_id,version,spec,created_at FROM scheduler.jobs WHERE job_id=:id")
                .param("id", job)
                .update();
        jdbc.sql(
                        "INSERT INTO scheduler.schedules(schedule_id,target_type,team_id,endpoint_id,shard_id,kind,interval_ms,dst_policy,misfire_policy,definition_version) VALUES (:id,1,:team,:endpoint,:shard,3,1000,1,3,1)")
                .param("id", job)
                .param("team", team)
                .param("endpoint", endpoint)
                .param("shard", shard)
                .update();
        return job;
    }

    UUID job(int shard) {
        return job(shard, "{\"schedule\":{\"type\":\"INTERVAL\"}}");
    }

    UUID run(UUID job) {
        int shard = (int) (job.getLeastSignificantBits() & 255);
        UUID run = ids.newRunId(Shard.of(shard)).value();
        // Distinct occurrence even when several runs are seeded at the same simulated instant.
        OffsetDateTime scheduled = now().plusNanos(Math.floorMod(run.hashCode(), 1000000) * 1000L);
        cells.forShard(shard)
                .jdbc()
                .sql("""
                INSERT INTO scheduler.run_inflight(shard_id,run_id,team_id,target_type,definition_id,definition_version,target_id,
                endpoint_id,endpoint_version,scheduled_at,due_at,state,series_started_at,fence,priority,trigger,owner_node,lease_until)
                VALUES (:shard,:id,:team,1,:job,1,:job,:endpoint,1,:scheduled,:now,3,:now,2,5,1,'executor-test',:now)
                """)
                .param("shard", shard)
                .param("id", run)
                .param("team", team)
                .param("job", job)
                .param("endpoint", endpoint)
                .param("scheduled", scheduled)
                .param("now", now())
                .update();
        return run;
    }

    Result result(UUID run, int outcome, int error, int http) {
        return cell(run)
                .jdbc()
                .sql("SELECT fence,attempt,infra_attempt FROM scheduler.run_inflight WHERE run_id=:id")
                .param("id", run)
                .query((rs, n) -> Result.newBuilder()
                        .setRunId(ProtoIds.toBytes(run))
                        .setShard((int) (run.getLeastSignificantBits() & 255))
                        .setFence(rs.getInt(1))
                        .setAttempt(rs.getInt(2))
                        .setInfraAttempt(rs.getInt(3))
                        .setOutcomeValue(outcome)
                        .setErrorClassValue(error)
                        .setHttpStatus(http)
                        .setStartedAt(ProtoTimestamps.toTimestamp(clock.instant()))
                        .setFinishedAt(
                                ProtoTimestamps.toTimestamp(clock.instant().plusMillis(10)))
                        .setLatencyMs(10)
                        .setExecutorId("executor-test")
                        .build())
                .single();
    }

    JdbcResultSettler settler() {
        return settler(WorkflowResultHandler.pending());
    }

    JdbcResultSettler settler(WorkflowResultHandler workflows) {
        return settler(workflows, List.of());
    }

    JdbcResultSettler settler(WorkflowResultHandler workflows, List<SettlementBatch.Observer> extra) {
        List<SettlementBatch.Observer> observers = new ArrayList<>(extra);
        observers.add(observed::addAll);
        return new JdbcResultSettler(
                cells,
                new SettlementPolicyLoader(control, JsonMapper.shared()),
                new ResultDecider(clock, new RetryPlanner(clock, () -> new Random(0))),
                workflows,
                observers,
                new OutboxWriter(new CellTransactionGuard(cells), ids, clock),
                ids,
                clock,
                JsonMapper.shared(),
                meters);
    }

    Cell cell(UUID id) {
        return cells.forShard((int) (id.getLeastSignificantBits() & 255));
    }

    OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    long count(UUID id, String table) {
        if (table.equals("outbox"))
            return cell(id)
                    .jdbc()
                    .sql("SELECT topic,payload FROM scheduler.outbox WHERE topic IN (5,6)")
                    .query((rs, n) -> {
                        try {
                            var run = rs.getInt(1) == 5
                                    ? dev.kairos.proto.v1.Completed.parseFrom(rs.getBytes(2))
                                            .getRunId()
                                    : dev.kairos.proto.v1.Dead.parseFrom(rs.getBytes(2))
                                            .getRunId();
                            return ProtoIds.toUuid(run).equals(id);
                        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .list()
                    .stream()
                    .filter(Boolean::booleanValue)
                    .count();
        return cell(id).jdbc()
                .sql("SELECT count(*) FROM scheduler." + table + " WHERE run_id=:id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    int value(UUID id, String table, String column, String key) {
        return cell(id).jdbc()
                .sql("SELECT " + column + " FROM scheduler." + table + " WHERE " + key + "=:id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    void claimRetry(UUID run) {
        Instant due = cell(run)
                .jdbc()
                .sql("SELECT due_at FROM scheduler.run_inflight WHERE run_id=:id")
                .param("id", run)
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .single();
        clock.set(due);
        cell(run)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET state=3,fence=fence+2 WHERE run_id=:id")
                .param("id", run)
                .update();
    }
}
