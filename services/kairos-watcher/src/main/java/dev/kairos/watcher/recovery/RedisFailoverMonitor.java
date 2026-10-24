package dev.kairos.watcher.recovery;

import dev.kairos.engine.lock.RedisTopology;
import dev.kairos.engine.lock.RedisTopologyProbe;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/** Quarantines absent sent markers after a Redis primary identity change. */
public final class RedisFailoverMonitor {
    private static final String FLAG = "redis_marker_quarantine_until";
    private static final String UPSERT = """
            INSERT INTO scheduler.scheduler_flags (name, value_ts, updated_at)
            VALUES (:name, :until, :now)
            ON CONFLICT (name) DO UPDATE
               SET value_ts = GREATEST(scheduler.scheduler_flags.value_ts, EXCLUDED.value_ts),
                   updated_at = EXCLUDED.updated_at
            """;
    private final RedisTopologyProbe probe;
    private final JdbcClient control;
    private final Clock clock;
    private final MeterRegistry meters;
    private String lastReplicationId;

    /** Creates a monitor shared by this watcher instance. */
    public RedisFailoverMonitor(RedisTopologyProbe probe, JdbcClient control, Clock clock, MeterRegistry meters) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.control = Objects.requireNonNull(control, "control");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /** Samples Redis every two seconds and extends quarantine on a changed primary id. */
    @Scheduled(fixedDelay = 2_000)
    public synchronized void poll() {
        RedisTopology topology = probe.read().orElse(null);
        if (topology == null || topology.role() != RedisTopology.Role.PRIMARY) return;
        String current = topology.replicationId();
        if (lastReplicationId != null && !lastReplicationId.equals(current)) {
            Instant now = clock.instant();
            control.sql(UPSERT)
                    .param("name", FLAG)
                    .param("until", Timestamp.from(now.plusSeconds(310)))
                    .param("now", Timestamp.from(now))
                    .update();
            meters.counter("kairos_redis_failover_quarantines_total").increment();
        }
        lastReplicationId = current;
    }

    /** Returns whether absent markers must be treated as unknown outcomes. */
    public boolean quarantined(Instant now) {
        return control.sql("SELECT value_ts FROM scheduler.scheduler_flags WHERE name = :name")
                .param("name", FLAG)
                .query((rs, row) -> rs.getTimestamp(1))
                .optional()
                .map(until -> until.toInstant().isAfter(now))
                .orElse(false);
    }
}
