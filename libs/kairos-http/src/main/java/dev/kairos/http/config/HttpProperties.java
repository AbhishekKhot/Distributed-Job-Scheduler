package dev.kairos.http.config;

import dev.kairos.http.client.HttpClientSettings;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Outbound HTTP client settings, bound from {@code kairos.http.*}. The response cap (256 KB), the TLS versions and
 * the disabled redirects are fixed and have no setting.
 *
 * @param connectTimeout TCP connect and TLS handshake timeout
 * @param poolWaitTimeout how long a call waits for a pooled connection
 * @param maxConnectionsTotal pool size of one client
 * @param maxConnectionsPerRoute pool size per target host and port
 * @param maxCaBundleClients CA-bundle clients kept open
 * @param maxCachedTokens OAuth 2.0 tokens cached
 * @param secretHeaders header names redacted in addition to {@code Authorization}, {@code Cookie} and
 *     {@code Set-Cookie}
 */
@Validated
@ConfigurationProperties("kairos.http")
public record HttpProperties(
        @NotNull @DefaultValue("3s") Duration connectTimeout,
        @NotNull @DefaultValue("3s") Duration poolWaitTimeout,
        @Min(1) @Max(100_000) @DefaultValue("4096") int maxConnectionsTotal,
        @Min(1) @Max(100_000) @DefaultValue("200") int maxConnectionsPerRoute,
        @Min(1) @Max(10_000) @DefaultValue("256") int maxCaBundleClients,
        @Min(1) @Max(1_000_000) @DefaultValue("10000") int maxCachedTokens,

        @NotNull @Size(max = 64) @DefaultValue({"X-Api-Key", "X-Auth-Token"})
        List<String> secretHeaders) {

    /**
     * Returns the client settings.
     *
     * @return the settings
     */
    public HttpClientSettings toSettings() {
        return new HttpClientSettings(
                connectTimeout, poolWaitTimeout, maxConnectionsTotal, maxConnectionsPerRoute, maxCaBundleClients);
    }
}
