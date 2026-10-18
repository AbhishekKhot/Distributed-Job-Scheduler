/**
 * Persistence adapters for the control and cell databases.
 *
 * <p>Owns data source routing by shard ({@code CellRouter}), repositories implementing the
 * domain ports, and the outbox writer. All SQL is parameterised and set-based.
 */
package dev.kairos.persistence;
