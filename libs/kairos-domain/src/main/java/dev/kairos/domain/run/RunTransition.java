package dev.kairos.domain.run;

import java.util.Objects;

/**
 * One allowed move of a run, as returned by {@link RunStateMachine#transition}.
 *
 * @param from the status before
 * @param to the status after
 * @param fence the fence to store with the new status
 * @param startsNewSeries true when a finished run is reopened (redrive or workflow retry): the
 *     caller sets {@code series_start} to the next attempt and {@code series_started_at} to now
 */
public record RunTransition(RunStatus from, RunStatus to, Fence fence, boolean startsNewSeries) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if a component is null
     */
    public RunTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(fence, "fence");
    }
}
