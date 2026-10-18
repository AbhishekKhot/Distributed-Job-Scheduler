/**
 * Execution engine.
 *
 * <p>Fire and misfire planning, the Redis run lock, fenced claims, settlement planning for
 * results (history, retries, dead letters, workflow progress) and outbox message building.
 * Planners are pure functions over domain types; side effects go through ports.
 */
package dev.kairos.engine;
