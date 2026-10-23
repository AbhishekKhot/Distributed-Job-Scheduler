package dev.kairos.api.team.application;

/**
 * What a team uses now (LLD section 3.4, API 9), summed over every cell.
 *
 * @param jobs jobs that are not deleted
 * @param execPerSec executions per second over the last complete minute
 * @param inflight runs executing now
 */
public record TeamUsage(long jobs, long execPerSec, long inflight) {

    /**
     * Validates the counters.
     *
     * @throws IllegalArgumentException if a counter is negative
     */
    public TeamUsage {
        if (jobs < 0 || execPerSec < 0 || inflight < 0) {
            throw new IllegalArgumentException("usage counters cannot be negative");
        }
    }

    /**
     * Adds another cell's counters.
     *
     * @param other the other counters
     * @return the sum
     */
    public TeamUsage plus(TeamUsage other) {
        return new TeamUsage(jobs + other.jobs, execPerSec + other.execPerSec, inflight + other.inflight);
    }
}
