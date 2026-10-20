/**
 * Database access primitives: {@link dev.kairos.persistence.jdbc.DataSourceRegistry} holds one HikariCP pool per
 * database, {@link dev.kairos.persistence.jdbc.Database} gives each one a {@code JdbcClient} and batch helpers, and
 * {@link dev.kairos.persistence.jdbc.DatabaseTransactions} runs units of work in single-database transactions with a
 * deadline, error translation and a duration metric.
 */
package dev.kairos.persistence.jdbc;
