package dev.kairos.domain.schedule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What the fire poller does with one due schedule ({@link MisfireHandler#plan}).
 *
 * @param fires the occurrences to create runs for, oldest first
 * @param next the new {@code next_fire_at} (with its local time), or null if the schedule has no
 *     further occurrence (a fired one-time schedule, which is then deleted)
 * @param lastFiredLocal the new {@code last_fired_local}: the local time of the last entry of
 *     {@code fires}, or the previous value if nothing fires
 * @param misfired true if at least one occurrence was more than the threshold late, whether or
 *     not the policy ran it (for {@code kairos_misfires_total})
 */
public record FirePlan(
        List<Fire> fires, @Nullable Fire next, @Nullable LocalDateTime lastFiredLocal, boolean misfired) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code fires} is null
     */
    public FirePlan {
        fires = List.copyOf(Objects.requireNonNull(fires, "fires"));
    }
}
