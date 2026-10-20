/**
 * Persistence foundation for the control and cell databases.
 *
 * <p>Subpackages: {@code jdbc} (pools, clients, single-database transactions), {@code cell} (shard-to-cell routing from
 * {@code cell_map}), {@code tx} (transaction entry points for repositories), {@code error} (error translation and
 * bounded retries), {@code fanout} (team-wide lists across every cell), {@code health} (readiness per database) and
 * {@code config} (Spring wiring). All SQL is parameterised and set-based, and no transaction spans two databases.
 */
package dev.kairos.persistence;
