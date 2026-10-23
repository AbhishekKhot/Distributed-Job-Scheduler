package dev.kairos.api.team.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A team's quotas, the {@code quotas} column of {@code teams} (LLD section 2.2). New teams get the configured
 * defaults; operators change them with the maintenance CLI.
 *
 * @param maxJobs the most jobs the team may have
 * @param maxExecPerSec the most executions per second
 * @param maxInflight the most runs executing at once
 * @param maxWorkflowTasks the most tasks in one workflow
 * @param backfillPerSec the backfill pace, runs per second
 * @param redrivePerSec the DLQ redrive pace, runs per second
 */
public record TeamQuotas(
        int maxJobs, int maxExecPerSec, int maxInflight, int maxWorkflowTasks, int backfillPerSec, int redrivePerSec) {

    /** The JSON key of {@link #maxJobs()}. */
    public static final String MAX_JOBS = "maxJobs";

    /** The JSON key of {@link #maxExecPerSec()}. */
    public static final String MAX_EXEC_PER_SEC = "maxExecPerSec";

    /** The JSON key of {@link #maxInflight()}. */
    public static final String MAX_INFLIGHT = "maxInflight";

    /** The JSON key of {@link #maxWorkflowTasks()}. */
    public static final String MAX_WORKFLOW_TASKS = "maxWorkflowTasks";

    /** The JSON key of {@link #backfillPerSec()}. */
    public static final String BACKFILL_PER_SEC = "backfillPerSec";

    /** The JSON key of {@link #redrivePerSec()}. */
    public static final String REDRIVE_PER_SEC = "redrivePerSec";

    /**
     * Validates the quotas.
     *
     * @throws IllegalArgumentException if a quota is not positive
     */
    public TeamQuotas {
        if (maxJobs < 1
                || maxExecPerSec < 1
                || maxInflight < 1
                || maxWorkflowTasks < 1
                || backfillPerSec < 1
                || redrivePerSec < 1) {
            throw new IllegalArgumentException("every quota must be positive");
        }
    }

    /**
     * Returns the quotas as stored in {@code teams.quotas}, in the LLD's key order.
     *
     * @return the quotas by JSON key
     */
    public Map<String, Integer> asMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put(MAX_JOBS, maxJobs);
        map.put(MAX_EXEC_PER_SEC, maxExecPerSec);
        map.put(MAX_INFLIGHT, maxInflight);
        map.put(MAX_WORKFLOW_TASKS, maxWorkflowTasks);
        map.put(BACKFILL_PER_SEC, backfillPerSec);
        map.put(REDRIVE_PER_SEC, redrivePerSec);
        return map;
    }

    /**
     * Returns the stored quotas, taking each missing or unusable value from the defaults.
     *
     * @param stored the stored quotas by JSON key
     * @param defaults the configured defaults
     * @return the effective quotas
     */
    public static TeamQuotas withDefaults(Map<String, Long> stored, TeamQuotas defaults) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(defaults, "defaults");
        return new TeamQuotas(
                value(stored, MAX_JOBS, defaults.maxJobs),
                value(stored, MAX_EXEC_PER_SEC, defaults.maxExecPerSec),
                value(stored, MAX_INFLIGHT, defaults.maxInflight),
                value(stored, MAX_WORKFLOW_TASKS, defaults.maxWorkflowTasks),
                value(stored, BACKFILL_PER_SEC, defaults.backfillPerSec),
                value(stored, REDRIVE_PER_SEC, defaults.redrivePerSec));
    }

    private static int value(Map<String, Long> stored, String key, int fallback) {
        Long value = stored.get(key);
        return value == null || value < 1 || value > Integer.MAX_VALUE ? fallback : value.intValue();
    }
}
