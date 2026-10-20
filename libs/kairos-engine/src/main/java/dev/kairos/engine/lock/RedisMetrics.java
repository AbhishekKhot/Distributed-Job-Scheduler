package dev.kairos.engine.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The run lock's meters (IMPLEMENTATION_CHECKLIST section 4.9: {@code kairos_} names, bounded labels).
 *
 * <ul>
 *   <li>{@value #ACQUIRE} ({@code kairos_run_lock_acquire_total}), label {@code outcome}: acquired, held,
 *       unavailable;
 *   <li>{@value #SCRIPT_DURATION} ({@code kairos_redis_script_duration_seconds}), label {@code script};
 *   <li>{@value #UNAVAILABLE} ({@code kairos_redis_unavailable_total}), no labels: every Redis command that failed,
 *       timed out or was rejected; the {@code KairosRedisUnavailable} alert fires on it.
 * </ul>
 */
final class RedisMetrics {

    static final String ACQUIRE = "kairos.run.lock.acquire";
    static final String SCRIPT_DURATION = "kairos.redis.script.duration";
    static final String UNAVAILABLE = "kairos.redis.unavailable";

    private final MeterRegistry registry;
    private final Map<AcquireResult.Outcome, Counter> acquires = new EnumMap<>(AcquireResult.Outcome.class);
    private final Map<LuaScript, Timer> scripts = new EnumMap<>(LuaScript.class);
    private final Counter unavailable;

    RedisMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (AcquireResult.Outcome outcome : AcquireResult.Outcome.values()) {
            acquires.put(
                    outcome,
                    Counter.builder(ACQUIRE)
                            .description("Run lock acquisitions by outcome")
                            .tag("outcome", outcome.label())
                            .register(registry));
        }
        for (LuaScript script : LuaScript.values()) {
            scripts.put(
                    script,
                    Timer.builder(SCRIPT_DURATION)
                            .description("Latency of the run lock's Lua scripts, including failures")
                            .tag("script", script.label())
                            .register(registry));
        }
        unavailable = Counter.builder(UNAVAILABLE)
                .description("Redis commands that failed, timed out or were rejected")
                .register(registry);
    }

    void acquired(AcquireResult.Outcome outcome) {
        Objects.requireNonNull(acquires.get(outcome)).increment();
    }

    Timer.Sample startScript() {
        return Timer.start(registry);
    }

    void stopScript(Timer.Sample sample, LuaScript script) {
        sample.stop(Objects.requireNonNull(scripts.get(script)));
    }

    void unavailable() {
        unavailable.increment();
    }
}
