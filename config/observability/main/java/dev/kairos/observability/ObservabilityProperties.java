package dev.kairos.observability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Observability settings shared by every Kairos application, bound from {@code kairos.observability.*}
 * (environment variables {@code KAIROS_OBSERVABILITY_*}).
 *
 * @param instance value of the {@code instance} tag on every metric; defaults to the host name
 *     (the pod or container name)
 * @param cell value of the {@code cell} tag for applications bound to one cell, for example
 *     {@code cell-0}; absent for applications that serve every cell
 * @param scheduleLagSlo bucket boundaries of the {@code kairos_schedule_lag_seconds} histogram,
 *     dense around the 2 s p99 objective (HLD section 12)
 * @param tracing trace export and sampling
 */
@Validated
@ConfigurationProperties("kairos.observability")
public record ObservabilityProperties(
        @NotNull @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$") String instance,
        @Nullable @Pattern(regexp = "^[a-z0-9-]{1,32}$") String cell,

        @DefaultValue({"100ms", "250ms", "500ms", "1s", "1500ms", "2s", "2500ms", "3s", "5s", "10s", "30s", "60s"})
        @NotEmpty @Size(max = 32) List<@NotNull Duration> scheduleLagSlo,

        @DefaultValue @Valid @NotNull Tracing tracing) {

    /**
     * Trace settings. Export is off by default, so a local stack needs no collector; sampling
     * applies whether or not spans are exported, because trace ids still appear in the logs.
     *
     * @param exportEnabled whether spans are sent to the OTLP endpoint
     * @param endpoint OTLP/HTTP traces endpoint, for example {@code http://otel-collector:4318/v1/traces};
     *     only {@code http} and {@code https} are accepted
     * @param samplingProbability share of new traces that are sampled, from 0 to 1
     */
    public record Tracing(
            @DefaultValue("false") boolean exportEnabled,
            @Nullable URI endpoint,

            @DefaultValue("0.1") @DecimalMin("0.0") @DecimalMax("1.0") double samplingProbability) {

        /**
         * Rejects endpoints that are not absolute HTTP(S) URLs, and an enabled export without one.
         *
         * @throws IllegalArgumentException if the settings are inconsistent
         */
        public Tracing {
            if (endpoint != null) {
                String scheme =
                        endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https") || endpoint.getHost() == null) {
                    throw new IllegalArgumentException(
                            "kairos.observability.tracing.endpoint must be an absolute http or https URL");
                }
            }
            if (exportEnabled && endpoint == null) {
                throw new IllegalArgumentException(
                        "kairos.observability.tracing.endpoint is required when export is enabled");
            }
        }
    }
}
