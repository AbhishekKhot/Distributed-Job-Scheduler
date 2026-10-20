package dev.kairos.engine.lock;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Redis connection of a service, bound from {@code kairos.redis.*}.
 *
 * <p>The host, port, ACL user name and password come from the environment ({@code KAIROS_REDIS_HOST},
 * {@code KAIROS_REDIS_PORT}, {@code KAIROS_REDIS_USERNAME}, {@code KAIROS_REDIS_PASSWORD}); nothing is hardcoded. The
 * ACL user may only touch {@code kairos:*} keys and run {@code INFO} besides the non-dangerous commands. Production
 * sets {@code tls=true}: the connection is then encrypted and the server certificate is verified (chain and host name)
 * against the JDK trust store, or against {@code trustCertificate} when set (for example the Memorystore server CA).
 *
 * @param host the Redis host name
 * @param port the Redis port
 * @param username the ACL user name
 * @param password the ACL user's password; never logged
 * @param tls whether to connect over TLS with full certificate verification
 * @param trustCertificate a PEM file of CA certificates to trust instead of the JDK trust store, or null
 * @param commandTimeout how long a command may take before it counts as failed (fail closed)
 * @param connectTimeout how long opening a connection may take
 * @param reconnectDelay the pause after a failed first connection before the next attempt
 * @param nodeId the node id written into run lock tokens, or null for the {@code HOSTNAME} environment variable (the
 *     pod name), falling back to a random id
 */
@Validated
@ConfigurationProperties("kairos.redis")
public record RedisProperties(
        @NotBlank @Size(max = 253) @Pattern(regexp = "^[A-Za-z0-9.-]+$") String host,

        @Min(1) @Max(65_535) @DefaultValue("6379") int port,

        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_.-]+$") String username,

        @NotBlank @Size(max = 512) String password,
        @DefaultValue("false") boolean tls,
        @Nullable @Size(max = 1024) String trustCertificate,
        @NotNull @DefaultValue("500ms") Duration commandTimeout,
        @NotNull @DefaultValue("2s") Duration connectTimeout,
        @NotNull @DefaultValue("1s") Duration reconnectDelay,

        @Nullable @Size(max = LockToken.MAX_NODE_ID_LENGTH) @Pattern(regexp = LockToken.NODE_ID_PATTERN) String nodeId) {

    /** Smallest accepted timeout or delay. */
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(10);

    /** Largest accepted timeout or delay. */
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Validates the timeouts, which Bean Validation cannot range-check on a {@link Duration}.
     *
     * @throws IllegalArgumentException if a timeout or delay is outside 10 ms to 30 s
     */
    public RedisProperties {
        requireRange(Objects.requireNonNull(commandTimeout, "commandTimeout"), "commandTimeout");
        requireRange(Objects.requireNonNull(connectTimeout, "connectTimeout"), "connectTimeout");
        requireRange(Objects.requireNonNull(reconnectDelay, "reconnectDelay"), "reconnectDelay");
    }

    /**
     * Creates plain-text settings with the default timeouts and no fixed node id, for tests and tools.
     *
     * @param host the Redis host name
     * @param port the Redis port
     * @param username the ACL user name
     * @param password the ACL user's password
     * @return the settings
     */
    public static RedisProperties of(String host, int port, String username, String password) {
        return new RedisProperties(
                host,
                port,
                username,
                password,
                false,
                null,
                Duration.ofMillis(500),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                null);
    }

    /** Leaves the password out, so the settings can never end up in a log line. */
    @Override
    public String toString() {
        return "RedisProperties[host=" + host + ", port=" + port + ", username=" + username
                + ", password=<redacted>, tls=" + tls + ", trustCertificate=" + trustCertificate + ", commandTimeout="
                + commandTimeout + ", connectTimeout=" + connectTimeout + ", reconnectDelay=" + reconnectDelay
                + ", nodeId=" + nodeId + "]";
    }

    private static void requireRange(Duration value, String name) {
        if (value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(name + " must be between 10ms and 30s");
        }
    }
}
