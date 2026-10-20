package dev.kairos.http.client;

import java.time.Duration;
import java.util.Objects;

/**
 * Connection settings shared by every outbound client.
 *
 * @param connectTimeout the TCP connect timeout, also the TLS handshake timeout (default 3s)
 * @param poolWaitTimeout how long a call waits for a pooled connection before failing unsent
 * @param maxConnectionsTotal the pool size of one client
 * @param maxConnectionsPerRoute the pool size per target host and port
 * @param maxCaBundleClients how many CA-bundle clients are kept before the least recently used is closed
 */
public record HttpClientSettings(
        Duration connectTimeout,
        Duration poolWaitTimeout,
        int maxConnectionsTotal,
        int maxConnectionsPerRoute,
        int maxCaBundleClients) {

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if a value is out of range
     */
    public HttpClientSettings {
        requireRange(connectTimeout, "connectTimeout");
        requireRange(poolWaitTimeout, "poolWaitTimeout");
        if (maxConnectionsTotal < 1 || maxConnectionsPerRoute < 1 || maxConnectionsPerRoute > maxConnectionsTotal) {
            throw new IllegalArgumentException("pool sizes are positive and the per-route size is within the total");
        }
        if (maxCaBundleClients < 1) {
            throw new IllegalArgumentException("maxCaBundleClients is positive");
        }
    }

    /**
     * Returns the defaults: 3s connect, 3s pool wait, 4096 connections per client, 200 per route, 256 CA-bundle
     * clients.
     *
     * @return the defaults
     */
    public static HttpClientSettings defaults() {
        return new HttpClientSettings(Duration.ofSeconds(3), Duration.ofSeconds(3), 4096, 200, 256);
    }

    private static void requireRange(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(Duration.ofMillis(100)) < 0 || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(name + " is 100ms to 30s");
        }
    }
}
