package dev.kairos.messaging.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Kafka connection and client settings of a service, bound from {@code kairos.kafka.*}.
 *
 * <p>The bootstrap servers come from {@code KAIROS_KAFKA_BOOTSTRAP_SERVERS}. The local stack connects in plain text
 * inside its private network; production sets {@code security-protocol=SASL_SSL} (Managed Kafka) with the SASL user
 * name and password from the environment ({@code KAIROS_KAFKA_USERNAME}, {@code KAIROS_KAFKA_PASSWORD}), and the
 * broker certificate is verified (chain and host name) against the JDK trust store, or against
 * {@code trustCertificate} when set. SASL over plain text is not offered. Nothing is hardcoded and the password is never
 * logged.
 *
 * @param bootstrapServers comma-separated {@code host:port} pairs
 * @param securityProtocol plain text (local only), TLS, or SASL over TLS
 * @param saslMechanism the SASL mechanism when the protocol is {@code SASL_SSL}
 * @param username the SASL user name, required for {@code SASL_SSL}
 * @param password the SASL password, required for {@code SASL_SSL}; never logged
 * @param trustCertificate a PEM file of CA certificates to trust instead of the JDK trust store, or null
 * @param clientIdPrefix the prefix of the client ids, normally the service name
 * @param producer producer settings
 * @param consumer consumer settings
 */
@Validated
@ConfigurationProperties("kairos.kafka")
public record KafkaProperties(
        @NotBlank @Size(max = 2048) @Pattern(regexp = "^[A-Za-z0-9.-]+:[0-9]{1,5}(,[A-Za-z0-9.-]+:[0-9]{1,5})*$")
        String bootstrapServers,

        @NotNull @DefaultValue("PLAINTEXT") SecurityProtocol securityProtocol,
        @NotNull @DefaultValue("PLAIN") SaslMechanism saslMechanism,

        // Quotes, backslashes and control characters would break out of the JAAS configuration line.
        @Nullable @Size(max = 256) @Pattern(regexp = "^[\\x21\\x23-\\x5B\\x5D-\\x7E]+$") String username,

        @Nullable @Size(max = 4096) @Pattern(regexp = "^[\\x21\\x23-\\x5B\\x5D-\\x7E]+$") String password,

        @Nullable @Size(max = 1024) String trustCertificate,

        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") @DefaultValue("kairos")
        String clientIdPrefix,

        @NotNull @Valid @DefaultValue Producer producer,
        @NotNull @Valid @DefaultValue Consumer consumer) {

    /** How a client connects to the brokers. */
    public enum SecurityProtocol {
        /** Unencrypted; only for the local stack's private network. */
        PLAINTEXT,
        /** TLS with certificate verification. */
        SSL,
        /** SASL authentication over TLS with certificate verification. */
        SASL_SSL
    }

    /** SASL mechanisms. */
    public enum SaslMechanism {
        /** User name and password (Managed Kafka). */
        PLAIN("PLAIN", "org.apache.kafka.common.security.plain.PlainLoginModule"),
        /** SCRAM with SHA-256. */
        SCRAM_SHA_256("SCRAM-SHA-256", "org.apache.kafka.common.security.scram.ScramLoginModule"),
        /** SCRAM with SHA-512. */
        SCRAM_SHA_512("SCRAM-SHA-512", "org.apache.kafka.common.security.scram.ScramLoginModule");

        private final String kafkaName;
        private final String loginModule;

        SaslMechanism(String kafkaName, String loginModule) {
            this.kafkaName = kafkaName;
            this.loginModule = loginModule;
        }

        /**
         * Returns the {@code sasl.mechanism} value.
         *
         * @return the Kafka name, for example {@code SCRAM-SHA-512}
         */
        public String kafkaName() {
            return kafkaName;
        }

        /**
         * Returns the JAAS login module class.
         *
         * @return the class name
         */
        public String loginModule() {
            return loginModule;
        }
    }

    /**
     * Producer settings (IMPLEMENTATION_CHECKLIST section 4.7: {@code acks=all}, idempotence and lz4 are fixed).
     *
     * @param deliveryTimeout upper bound on reporting a send's success or failure, retries included; 5 s to 5 min
     * @param requestTimeout how long the producer waits for one broker response; 1 s to 2 min and below the delivery
     *     timeout
     * @param linger how long a batch waits for more records; 0 to 1 s
     * @param batchSizeBytes the target batch size per partition; 16 KiB to 4 MiB
     * @param maxBlock how long {@code send} may block on metadata or a full buffer; 0 to 1 min
     */
    public record Producer(
            @NotNull @DefaultValue("30s") Duration deliveryTimeout,
            @NotNull @DefaultValue("10s") Duration requestTimeout,
            @NotNull @DefaultValue("5ms") Duration linger,

            @Min(16_384) @Max(4_194_304) @DefaultValue("262144")
            int batchSizeBytes,

            @NotNull @DefaultValue("10s") Duration maxBlock) {

        /**
         * Validates the durations, which Bean Validation cannot range-check.
         *
         * @throws IllegalArgumentException if a duration is out of range
         */
        public Producer {
            requireRange(deliveryTimeout, Duration.ofSeconds(5), Duration.ofMinutes(5), "deliveryTimeout");
            requireRange(requestTimeout, Duration.ofSeconds(1), Duration.ofMinutes(2), "requestTimeout");
            requireRange(linger, Duration.ZERO, Duration.ofSeconds(1), "linger");
            requireRange(maxBlock, Duration.ZERO, Duration.ofMinutes(1), "maxBlock");
            // Kafka requires delivery.timeout.ms >= linger.ms + request.timeout.ms.
            if (deliveryTimeout.compareTo(requestTimeout.plus(linger)) < 0) {
                throw new IllegalArgumentException("deliveryTimeout must be at least requestTimeout + linger");
            }
        }
    }

    /**
     * Consumer settings (IMPLEMENTATION_CHECKLIST section 4.7: manual commits and the cooperative-sticky assignor are
     * fixed).
     *
     * @param maxPollRecords the most records one poll returns; 1 to 10,000 (LLD section 4.1: 250 / 150 / 100 for the
     *     dispatch topics, set per consumer)
     * @param maxPollInterval the longest gap between polls before the consumer leaves its group; 10 s to 30 min
     * @param sessionTimeout how long the group coordinator waits for heartbeats; 6 s to 5 min
     * @param autoOffsetReset where a new group starts: {@code earliest} or {@code latest}
     */
    public record Consumer(
            @Min(1) @Max(10_000) @DefaultValue("500") int maxPollRecords,
            @NotNull @DefaultValue("5m") Duration maxPollInterval,
            @NotNull @DefaultValue("45s") Duration sessionTimeout,

            @NotNull @Pattern(regexp = "^(earliest|latest)$") @DefaultValue("earliest")
            String autoOffsetReset) {

        /**
         * Validates the durations, which Bean Validation cannot range-check.
         *
         * @throws IllegalArgumentException if a duration is out of range
         */
        public Consumer {
            requireRange(maxPollInterval, Duration.ofSeconds(10), Duration.ofMinutes(30), "maxPollInterval");
            requireRange(sessionTimeout, Duration.ofSeconds(6), Duration.ofMinutes(5), "sessionTimeout");
        }
    }

    /**
     * Validates the combination of protocol and credentials, and the trust certificate file.
     *
     * @throws IllegalArgumentException if {@code SASL_SSL} is missing a user name or password, or the trust
     *     certificate is not a readable file
     */
    public KafkaProperties {
        Objects.requireNonNull(securityProtocol, "securityProtocol");
        if (securityProtocol == SecurityProtocol.SASL_SSL
                && (username == null || username.isEmpty() || password == null || password.isEmpty())) {
            throw new IllegalArgumentException("SASL_SSL needs kairos.kafka.username and kairos.kafka.password");
        }
        if (trustCertificate != null && !trustCertificate.isBlank()) {
            Path file = Path.of(trustCertificate);
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new IllegalArgumentException("kairos.kafka.trust-certificate is not a readable file");
            }
        }
    }

    /**
     * Creates plain-text settings with the defaults, for tests and tools.
     *
     * @param bootstrapServers the bootstrap servers
     * @return the settings
     */
    public static KafkaProperties plaintext(String bootstrapServers) {
        return new KafkaProperties(
                bootstrapServers,
                SecurityProtocol.PLAINTEXT,
                SaslMechanism.PLAIN,
                null,
                null,
                null,
                "kairos",
                new Producer(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        Duration.ofMillis(5),
                        262_144,
                        Duration.ofSeconds(10)),
                new Consumer(500, Duration.ofMinutes(5), Duration.ofSeconds(45), "earliest"));
    }

    /** Keeps the password out of logs and error messages. */
    @Override
    public String toString() {
        return "KafkaProperties[bootstrapServers=" + bootstrapServers + ", securityProtocol=" + securityProtocol
                + ", saslMechanism=" + saslMechanism + ", username=" + username + ", password="
                + (password == null ? "null" : "<redacted>") + ", trustCertificate=" + trustCertificate
                + ", clientIdPrefix=" + clientIdPrefix + ", producer=" + producer + ", consumer=" + consumer + "]";
    }

    private static void requireRange(Duration value, Duration min, Duration max, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be " + min + " to " + max);
        }
    }
}
