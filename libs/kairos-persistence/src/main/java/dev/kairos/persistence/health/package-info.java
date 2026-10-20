/**
 * Readiness checks per database: {@link dev.kairos.persistence.health.DatabaseHealthIndicator}, grouped into one
 * composite contributor with a child per database so each service can depend on exactly the cells it needs.
 */
package dev.kairos.persistence.health;
