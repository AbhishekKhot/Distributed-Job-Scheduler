/**
 * Observability baseline shared by every Kairos application: OpenTelemetry tracing defaults, common
 * metric tags, the schedule-lag SLO histogram and redaction of credentials in structured logs.
 *
 * <p>The sources live in {@code config/observability} and are compiled into each application by the
 * {@code kairos.service-conventions} plugin, so every application gets the same behaviour without
 * one application depending on another. Spring Boot picks the classes up through
 * {@code META-INF/spring.factories} and the auto-configuration imports file next to it.
 */
@NullMarked
package dev.kairos.observability;

import org.jspecify.annotations.NullMarked;
