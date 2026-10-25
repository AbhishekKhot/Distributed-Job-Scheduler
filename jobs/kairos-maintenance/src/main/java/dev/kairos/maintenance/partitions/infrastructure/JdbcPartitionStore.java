package dev.kairos.maintenance.partitions.infrastructure;

import dev.kairos.maintenance.partitions.application.PartitionDay;
import dev.kairos.maintenance.partitions.application.PartitionStore;
import dev.kairos.maintenance.partitions.application.PartitionTable;
import dev.kairos.persistence.jdbc.Database;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps the advisory lock on one physical session; each DDL/data-move unit has its own bounded transaction. */
public final class JdbcPartitionStore implements PartitionStore {
    private static final System.Logger LOG = System.getLogger(JdbcPartitionStore.class.getName());
    public static final long LOCK_KEY = 0x4b4149524f530281L;
    private static final Pattern BOUNDS = Pattern.compile("^FOR VALUES FROM \\('([^']+)'\\) TO \\('([^']+)'\\)$");
    private static final String RELATIONS = """
            SELECT c.relname,n.nspname,c.relkind,c.relispartition,pg_get_expr(c.relpartbound,c.oid) AS bounds,
                p.relname AS parent,pn.nspname AS parent_schema
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            LEFT JOIN pg_catalog.pg_inherits i ON i.inhrelid=c.oid
            LEFT JOIN pg_catalog.pg_class p ON p.oid=i.inhparent
            LEFT JOIN pg_catalog.pg_namespace pn ON pn.oid=p.relnamespace
            """;
    private final Database database;
    private final List<PartitionTable> tables;
    private final PartitionProperties properties;
    private final MeterRegistry meters;

    public JdbcPartitionStore(
            Database database, List<PartitionTable> tables, PartitionProperties properties, MeterRegistry meters) {
        this.database = database;
        this.tables = List.copyOf(tables);
        this.properties = properties;
        this.meters = meters;
        if (tables.isEmpty() || tables.stream().distinct().count() != tables.size())
            throw new IllegalArgumentException("Invalid partition table group");
    }

    @Override
    public Result maintain(LocalDate today) {
        try (Connection connection = database.dataSource().getConnection()) {
            if (!connection.getAutoCommit())
                throw new IllegalStateException("Partition maintenance requires an idle auto-commit connection");
            var source = new SingleConnectionDataSource(connection, true);
            var template = new JdbcTemplate(source);
            template.setQueryTimeout(seconds(properties.statementTimeout()));
            JdbcClient jdbc = JdbcClient.create(template);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            transactions.setTimeout(seconds(properties.transactionTimeout()));
            Session session = new Session(jdbc, transactions);
            boolean acquired = jdbc.sql("SELECT pg_try_advisory_lock(:key)")
                    .param("key", LOCK_KEY)
                    .query(Boolean.class)
                    .single();
            if (!acquired) {
                metric("skipped", 1);
                return new Result(database.name(), true, 0, 0, 0, 0);
            }
            try {
                session.verifyOwner();
                int created = 0, dropped = 0;
                long moved = 0, purged = 0;
                for (PartitionTable table : tables) {
                    for (int offset = 0; offset <= 3; offset++) {
                        Creation creation = session.create(new PartitionDay(table, today.plusDays(offset)));
                        if (creation.created()) created++;
                        moved += creation.moved();
                    }
                    dropped += session.dropExpired(table, today.minusDays(table.retentionDays()));
                    purged += session.purgeExpiredDefault(table, today.minusDays(table.retentionDays()));
                }
                metric("created", created);
                metric("moved", moved);
                metric("dropped", dropped);
                metric("purged", purged);
                return new Result(database.name(), false, created, moved, dropped, purged);
            } finally {
                try {
                    boolean released = jdbc.sql("SELECT pg_advisory_unlock(:key)")
                            .param("key", LOCK_KEY)
                            .query(Boolean.class)
                            .single();
                    if (!released) throw new IllegalStateException("Partition maintenance lock was lost");
                } catch (RuntimeException failure) {
                    connection.abort(Runnable::run);
                    throw failure;
                }
            }
        } catch (SQLException failure) {
            failed(failure);
            throw new IllegalStateException("Partition maintenance connection failed", failure);
        } catch (RuntimeException failure) {
            failed(failure);
            throw failure;
        }
    }

    private void failed(Exception failure) {
        metric("failed", 1);
        LOG.log(
                System.Logger.Level.ERROR,
                "Partition maintenance failed for {0}: {1}",
                database.name(),
                failure.getClass().getSimpleName());
    }

    private void metric(String operation, long count) {
        if (count > 0)
            meters.counter("kairos.maintenance.partitions", "database", database.name(), "operation", operation)
                    .increment(count);
    }

    private static int seconds(java.time.Duration duration) {
        return Math.toIntExact((duration.toMillis() + 999) / 1000);
    }

    private final class Session {
        private final JdbcClient jdbc;
        private final TransactionTemplate transactions;

        Session(JdbcClient jdbc, TransactionTemplate transactions) {
            this.jdbc = jdbc;
            this.transactions = transactions;
        }

        private <T> T transaction(Supplier<T> action) {
            return transactions.execute(status -> {
                jdbc.sql(
                                "SELECT set_config('TimeZone','UTC',true),set_config('lock_timeout',:lock,true),set_config('statement_timeout',:statement,true)")
                        .param("lock", properties.lockTimeout().toMillis() + "ms")
                        .param("statement", properties.statementTimeout().toMillis() + "ms")
                        .query((rs, n) -> 1)
                        .single();
                return action.get();
            });
        }

        void verifyOwner() {
            boolean owner = jdbc.sql("""
                    SELECT count(*)=:count AND coalesce(bool_and(c.relkind='p' AND pg_has_role(current_user,c.relowner,'USAGE')),false)
                    FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='scheduler' AND c.relname IN (:tables)
                    """)
                    .param("count", tables.size())
                    .param("tables", tables.stream().map(PartitionTable::table).toList())
                    .query(Boolean.class)
                    .single();
            if (!owner)
                throw new IllegalStateException(
                        "Partition maintenance requires ownership of the configured parent tables");
        }

        Creation create(PartitionDay day) {
            return transaction(() -> {
                var existing = relation(day.name());
                if (existing.isPresent()) {
                    validate(existing.get(), day);
                    return new Creation(false, 0);
                }
                // Recheck after locking: migration-time partition creation uses the same parent lock.
                jdbc.sql(PartitionDdl.lockParent(day.table())).update();
                existing = relation(day.name());
                if (existing.isPresent()) {
                    validate(existing.get(), day);
                    return new Creation(false, 0);
                }
                var fallback = defaultPartition(day.table());
                if (fallback.isPresent())
                    jdbc.sql(PartitionDdl.lockDefault(day.table())).update();
                jdbc.sql(PartitionDdl.create(day)).update();
                int moved = fallback.isEmpty()
                        ? 0
                        : jdbc.sql(PartitionDdl.move(day))
                                .param("start", day.date().atStartOfDay().atOffset(ZoneOffset.UTC))
                                .param(
                                        "end",
                                        day.date().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                                .update();
                jdbc.sql(PartitionDdl.boundCheck(day)).update();
                jdbc.sql(PartitionDdl.attach(day)).update();
                validate(relation(day.name()).orElseThrow(), day);
                return new Creation(true, moved);
            });
        }

        int dropExpired(PartitionTable table, LocalDate cutoff) {
            List<Relation> children = transaction(
                    () -> jdbc.sql(RELATIONS + " WHERE pn.nspname='scheduler' AND p.relname=:parent ORDER BY c.relname")
                            .param("parent", table.table())
                            .query(JdbcPartitionStore::relation)
                            .list());
            int dropped = 0;
            for (Relation child : children) {
                if ("DEFAULT".equals(child.bounds()) || !child.schema().equals("scheduler")) continue;
                Optional<PartitionDay> day = PartitionDay.parse(table, child.name());
                if (day.isEmpty()) continue;
                // Even a familiar date-derived name is not sufficient authority to drop a table.
                transaction(() -> {
                    validate(child, day.get());
                    return 0;
                });
                if (!day.get().date().isBefore(cutoff)) continue;
                dropped += transaction(() -> {
                    jdbc.sql(PartitionDdl.lockParent(table)).update();
                    var current = relation(day.get().name());
                    if (current.isEmpty()) return 0;
                    validate(current.get(), day.get());
                    jdbc.sql(PartitionDdl.drop(day.get())).update();
                    return 1;
                });
            }
            return dropped;
        }

        long purgeExpiredDefault(PartitionTable table, LocalDate cutoff) {
            boolean present = transaction(() -> defaultPartition(table).isPresent());
            if (!present) return 0;
            long purged = 0;
            int removed;
            do {
                removed = transaction(() -> jdbc.sql(PartitionDdl.purgeDefault(table))
                        .param("cutoff", cutoff.atStartOfDay().atOffset(ZoneOffset.UTC))
                        .param("batch", properties.purgeBatchSize())
                        .update());
                purged += removed;
            } while (removed == properties.purgeBatchSize());
            return purged;
        }

        private Optional<Relation> defaultPartition(PartitionTable table) {
            var defaults = jdbc.sql(
                            RELATIONS
                                    + " WHERE pn.nspname='scheduler' AND p.relname=:parent AND pg_get_expr(c.relpartbound,c.oid)='DEFAULT'")
                    .param("parent", table.table())
                    .query(JdbcPartitionStore::relation)
                    .list();
            if (defaults.isEmpty()) return Optional.empty();
            if (defaults.size() != 1
                    || !defaults.getFirst().schema().equals("scheduler")
                    || !defaults.getFirst().name().equals(table.defaultName()))
                throw new IllegalStateException("Unexpected default partition layout");
            return Optional.of(defaults.getFirst());
        }

        private Optional<Relation> relation(String name) {
            return jdbc.sql(RELATIONS + " WHERE n.nspname='scheduler' AND c.relname=:name")
                    .param("name", name)
                    .query(JdbcPartitionStore::relation)
                    .optional();
        }

        private void validate(Relation relation, PartitionDay day) {
            if (!relation.schema().equals("scheduler")
                    || !relation.partition()
                    || !"r".equals(relation.kind())
                    || !"scheduler".equals(relation.parentSchema())
                    || !day.table().table().equals(relation.parent())
                    || relation.bounds() == null)
                throw new IllegalStateException("Partition name is occupied by a different relation");
            var bounds = BOUNDS.matcher(relation.bounds());
            if (!bounds.matches()) throw new IllegalStateException("Unexpected daily partition bound");
            boolean matches = jdbc.sql("SELECT CAST(:lower AS timestamptz)=:start AND CAST(:upper AS timestamptz)=:end")
                    .param("lower", bounds.group(1))
                    .param("upper", bounds.group(2))
                    .param("start", day.date().atStartOfDay().atOffset(ZoneOffset.UTC))
                    .param("end", day.date().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                    .query(Boolean.class)
                    .single();
            if (!matches) throw new IllegalStateException("Daily partition name and bounds disagree");
        }
    }

    private static Relation relation(java.sql.ResultSet rs, int row) throws SQLException {
        return new Relation(
                rs.getString("relname"),
                rs.getString("nspname"),
                rs.getString("relkind"),
                rs.getBoolean("relispartition"),
                rs.getString("bounds"),
                rs.getString("parent"),
                rs.getString("parent_schema"));
    }

    private record Relation(
            String name,
            String schema,
            String kind,
            boolean partition,
            @Nullable String bounds,
            @Nullable String parent,
            @Nullable String parentSchema) {}

    private record Creation(boolean created, int moved) {}
}
