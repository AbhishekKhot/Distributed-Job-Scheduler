/**
 * Schedules: what a job or workflow's {@code schedule} means and when it fires.
 *
 * <p>{@link dev.kairos.domain.schedule.ScheduleSpec} is the validated schedule
 * ({@code IMMEDIATE}, {@code ONE_TIME}, {@code CRON} or {@code INTERVAL}), built from the request
 * by {@link dev.kairos.domain.schedule.ScheduleRequest}. A
 * {@link dev.kairos.domain.schedule.ScheduleCalculator} per kind turns it into
 * {@link dev.kairos.domain.schedule.Fire} occurrences: cron expressions are matched on local
 * wall-clock times in an IANA zone and mapped to instants under the schedule's DST policy, with
 * {@code last_fired_local} preventing a second fire in a repeated hour.
 * {@link dev.kairos.domain.schedule.Spread} gives each schedule a fixed offset to smooth
 * top-of-minute spikes, and {@link dev.kairos.domain.schedule.MisfireHandler} decides which
 * occurrences a late poll creates. All calculations take the current time as an argument.
 */
package dev.kairos.domain.schedule;
