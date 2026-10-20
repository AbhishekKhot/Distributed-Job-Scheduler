/**
 * The Redis run lock and sent markers: gates 1, 3 and 4 of the four execution gates (HLD section 6).
 *
 * <ul>
 *   <li>{@link dev.kairos.engine.lock.RunLockPort}: the run lock {@code kairos:lock:run:{runId}} (LLD section 4.3).
 *       {@code acquire} takes it atomically with an owner token that encodes the fence, the node id and random bytes;
 *       {@code remaining} tells the send-time guard how long the caller still owns it; {@code release} and
 *       {@code extend} act only for the owner. Each operation is a Lua script, loaded once and invoked by SHA, with an
 *       automatic reload when Redis answers {@code NOSCRIPT} (after a restart or failover).
 *   <li>{@link dev.kairos.engine.lock.SentMarkerPort}: the sent marker
 *       {@code kairos:sent:{runId}:{attempt}:{infraAttempt}}, written just before a request leaves and read by the
 *       lease sweeper to tell a safe re-queue from an unknown outcome.
 *   <li>{@link dev.kairos.engine.lock.RedisTopologyProbe}: the replication id and role of the Redis primary, read by
 *       the failover monitor.
 * </ul>
 *
 * <p>Everything fails closed: a Redis error, a rejected command or a command timeout (500 ms by default) is reported
 * as an {@code UNAVAILABLE} result, never as success, and callers then do not call the target. Nothing in this package
 * throws for a Redis failure.
 *
 * <p>{@link dev.kairos.engine.lock.RunLockConfiguration} wires the Lettuce adapters from {@code kairos.redis.*}
 * ({@link dev.kairos.engine.lock.RedisProperties}). Lettuce types never appear in the public API.
 */
package dev.kairos.engine.lock;
