package dev.kairos.api.team;

import dev.kairos.api.team.application.TeamQuotas;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the team APIs, bound from {@code kairos.api.team.*} and documented with their defaults in
 * {@code application.yml}.
 *
 * @param defaultQuotas the quotas of a new team, also used for any quota a team's row does not set
 */
@Validated
@ConfigurationProperties("kairos.api.team")
public record TeamProperties(@NotNull @Valid @DefaultValue DefaultQuotas defaultQuotas) {

    /**
     * The quotas of a new team (LLD section 2.2, {@code teams.quotas}).
     *
     * @param maxJobs the most jobs
     * @param maxExecPerSec the most executions per second
     * @param maxInflight the most runs executing at once
     * @param maxWorkflowTasks the most tasks in one workflow
     * @param backfillPerSec the backfill pace, runs per second
     * @param redrivePerSec the DLQ redrive pace, runs per second
     */
    public record DefaultQuotas(
            @Min(1) @Max(10_000_000) @DefaultValue("10000") int maxJobs,
            @Min(1) @Max(1_000_000) @DefaultValue("2000") int maxExecPerSec,
            @Min(1) @Max(1_000_000) @DefaultValue("2000") int maxInflight,
            @Min(1) @Max(10_000) @DefaultValue("200") int maxWorkflowTasks,
            @Min(1) @Max(100_000) @DefaultValue("100") int backfillPerSec,
            @Min(1) @Max(100_000) @DefaultValue("100") int redrivePerSec) {

        /**
         * Returns the quotas.
         *
         * @return the default quotas
         */
        public TeamQuotas toQuotas() {
            return new TeamQuotas(maxJobs, maxExecPerSec, maxInflight, maxWorkflowTasks, backfillPerSec, redrivePerSec);
        }
    }
}
