package dev.kairos.domain.schedule;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One occurrence of a schedule.
 *
 * <p>{@code local} is the wall-clock time the occurrence stands for, stored as
 * {@code schedules.last_fired_local} once it fires. For a cron schedule it is the matching local
 * time in the schedule's zone, which differs from {@code at} in that zone only when the time fell
 * in a daylight-saving gap and was shifted forward. For one-time and interval schedules it is
 * {@code at} in UTC.
 *
 * @param at the instant the occurrence is scheduled for (UTC, {@code runs.scheduled_at})
 * @param local the local time it represents
 */
public record Fire(Instant at, LocalDateTime local) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if a component is null
     */
    public Fire {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(local, "local");
    }
}
