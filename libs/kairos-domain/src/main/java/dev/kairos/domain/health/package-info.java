/**
 * Automatic protection (HLD sections 3 and 10): job health and endpoint suspension.
 *
 * <p>{@link dev.kairos.domain.health.JobHealth} is the {@code job_health} row: the last 10 final
 * outcomes as a bitmask and the count of consecutive dead-lettered runs.
 * {@link dev.kairos.domain.health.AutoPause} pauses a job after {@code autoPauseAfter}
 * consecutive dead letters (3 by default, 0 disables it).
 * {@link dev.kairos.domain.health.EndpointSuspendRule} suspends an endpoint after 50 dead letters
 * in 5 minutes or 80% failures over at least 200 attempts, with a 10-minute cool-down.
 */
package dev.kairos.domain.health;
