/**
 * Schema management for the control and cell databases.
 *
 * <p>{@link dev.kairos.migrations.schema.MigrationRunner} drives the job. For each database it applies the Flyway
 * scripts of {@code db/control} or {@code db/cell}, creates the daily partitions, verifies the application role's
 * grants and, for the control database, seeds {@code cell_map}. All SQL issued from Java is parameterised; DDL that
 * needs identifiers runs inside {@code scheduler.create_day_partition}, which quotes them.
 */
package dev.kairos.migrations.schema;
