package dev.kairos.maintenance.partitions;

import dev.kairos.maintenance.partitions.application.PartitionManager;
import dev.kairos.maintenance.partitions.application.PartitionTable;
import dev.kairos.maintenance.partitions.infrastructure.JdbcPartitionStore;
import dev.kairos.maintenance.partitions.infrastructure.PartitionProperties;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.RepositoryFiles;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

public final class PartitionFixtures {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    static final PartitionProperties LIMITS =
            new PartitionProperties(Duration.ofMillis(200), Duration.ofSeconds(10), Duration.ofSeconds(30), 2);
    private static boolean ready;
    final KairosPostgres postgres;
    final MutableClock clock = MutableClock.at(Instant.parse("2026-10-25T23:30:00Z"));
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final Database control, cell0, cell1;

    PartitionFixtures(KairosPostgres postgres) {
        this.postgres = postgres;
        ensureSchema(postgres);
        control = db(Database.CONTROL, postgres.controlOwnerDataSource());
        cell0 = db(Database.cellName(0), postgres.cellOwnerDataSource(0));
        cell1 = db(Database.cellName(1), postgres.cellOwnerDataSource(1));
    }

    public static synchronized void ensureSchema(KairosPostgres postgres) {
        if (ready) return;
        apply(postgres.controlOwnerDataSource(), "control", postgres.appUsername());
        for (int i = 0; i < 2; i++) apply(postgres.cellOwnerDataSource(i), "cell", postgres.appUsername());
        ready = true;
    }

    private static void apply(DataSource source, String kind, String role) {
        try (var files = Files.list(RepositoryFiles.resolve(
                                "jobs/kairos-migrations/src/main/resources/db/" + kind + "/afterMigrate.sql")
                        .getParent());
                var connection = source.getConnection();
                var statement = connection.createStatement()) {
            for (var path : files.filter(p -> p.getFileName().toString().startsWith("V")
                            && p.toString().endsWith(".sql"))
                    .sorted()
                    .toList()) statement.execute(Files.readString(path).replace("${appRole}", role));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Database db(String name, DataSource source) {
        return Database.of(name, source, DatabaseOptions.defaults(), meters);
    }

    JdbcPartitionStore store(Database database, PartitionTable... tables) {
        return new JdbcPartitionStore(database, List.of(tables), LIMITS, meters);
    }

    PartitionManager manager() {
        return new PartitionManager(
                clock,
                List.of(
                        store(control, PartitionTable.AUDIT_LOG),
                        store(cell0, PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS),
                        store(cell1, PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS)));
    }

    void reset() {
        for (Database db : List.of(control, cell0, cell1)) {
            var jdbc = db.jdbc();
            for (String child : jdbc.sql("""
                    SELECT c.relname FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class c ON c.oid=i.inhrelid
                    JOIN pg_catalog.pg_class p ON p.oid=i.inhparent JOIN pg_catalog.pg_namespace n ON n.oid=p.relnamespace
                    WHERE n.nspname='scheduler' AND p.relname IN ('audit_log','run_history','task_outputs') AND c.relname NOT LIKE '%_default'
                    """).query(String.class).list())
                jdbc.sql("DROP TABLE scheduler.\"" + child.replace("\"", "\"\"") + "\"")
                        .update();
            for (PartitionTable table : db == control
                    ? List.of(PartitionTable.AUDIT_LOG)
                    : List.of(PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS))
                jdbc.sql("TRUNCATE scheduler." + table.table()).update();
        }
    }

    void daily(Database database, PartitionTable table, LocalDate date) {
        database.jdbc()
                .sql("SELECT scheduler.create_day_partition(:parent,:day)")
                .param("parent", table.table())
                .param("day", date)
                .query(String.class)
                .single();
    }

    UUID insert(Database database, PartitionTable table, LocalDate date) {
        UUID id = UUID.randomUUID(),
                team = TestIds.teamId().value(),
                workflow = TestIds.workflowRunId(12).value();
        var at = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        switch (table) {
            case AUDIT_LOG ->
                database.jdbc()
                        .sql(
                                "INSERT INTO scheduler.audit_log(audit_id,at,actor,action) VALUES (:id,:at,'system:test','TEST')")
                        .param("id", id)
                        .param("at", at)
                        .update();
            case TASK_OUTPUTS ->
                database.jdbc()
                        .sql(
                                "INSERT INTO scheduler.task_outputs(scheduled_at,workflow_run_id,task_key,team_id,outputs,created_at) VALUES (:at,:id,'task',:team,'{}',:at)")
                        .param("id", id)
                        .param("team", team)
                        .param("at", at)
                        .update();
            case RUN_HISTORY ->
                database.jdbc()
                        .sql("""
                    INSERT INTO scheduler.run_history(scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,
                    target_id,endpoint_id,trigger,outcome,final,finished_at,latency_ms)
                    VALUES (:at,:id,1,0,:team,1,:definition,:definition,:endpoint,1,1,true,:at,20)
                    """)
                        .param("id", id)
                        .param("at", at)
                        .param("team", team)
                        .param("definition", workflow)
                        .param("endpoint", TestIds.endpointId().value())
                        .update();
        }
        return id;
    }

    static boolean exists(Database database, String name) {
        return database.jdbc()
                .sql("SELECT to_regclass(:name) IS NOT NULL")
                .param("name", "scheduler." + name)
                .query(Boolean.class)
                .single();
    }

    static long count(Database database, String table) {
        return database.jdbc()
                .sql("SELECT count(*) FROM scheduler." + table)
                .query(Long.class)
                .single();
    }
}
