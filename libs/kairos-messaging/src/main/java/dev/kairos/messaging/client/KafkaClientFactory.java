package dev.kairos.messaging.client;

import com.google.protobuf.MessageLite;
import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.MessageType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * Creates the Kafka producers and consumers of a service with the settings of IMPLEMENTATION_CHECKLIST section 4.7,
 * and binds every client's metrics to the service's {@link MeterRegistry} ({@code kafka.producer.*} and
 * {@code kafka.consumer.*}).
 *
 * <p>Producers: {@code acks=all}, idempotence on, lz4 compression, a bounded {@code delivery.timeout.ms}, and records
 * up to {@value #MAX_REQUEST_BYTES} bytes per request. Consumers: no automatic commits (the caller commits after
 * processing), the cooperative-sticky assignor, a bounded {@code max.poll.records}, and {@code read_committed}
 * isolation. Keys are raw bytes (16-byte ids); consumer values are {@link Decoded}, so a bad value never throws in
 * {@code poll}.
 *
 * <p>Thread-safe. Each client it returns follows Kafka's own rules (a producer is thread-safe, a consumer is not).
 */
public final class KafkaClientFactory {

    /** Largest produce request: four maximum-size messages, and the brokers' default {@code message.max.bytes}. */
    public static final int MAX_REQUEST_BYTES = 4 * MessageType.MAX_PAYLOAD_BYTES;

    private static final Pattern GROUP_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private final KafkaProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger clientSequence = new AtomicInteger();

    /**
     * Creates the factory.
     *
     * @param properties the connection and client settings
     * @param meterRegistry where client metrics are registered
     */
    public KafkaClientFactory(KafkaProperties properties, MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /**
     * Creates a producer of raw keys and values (the outbox relay publishes stored payloads) with its metrics bound.
     *
     * @param name a short name for the client id, for example {@code outbox-relay}
     * @return the publisher; closing it closes the producer and unbinds its metrics
     */
    public KafkaPublisher publisher(String name) {
        Map<String, Object> config = producerConfig(name);
        KafkaProducer<byte[], byte[]> producer =
                new KafkaProducer<>(config, new ByteArraySerializer(), new ByteArraySerializer());
        KafkaClientMetrics metrics = new KafkaClientMetrics(producer);
        metrics.bindTo(meterRegistry);
        return new KafkaPublisher(producer, metrics);
    }

    /**
     * Creates a consumer of one message type with its metrics bound. The caller subscribes it and commits offsets
     * itself after processing.
     *
     * @param groupId the consumer group, 1 to 128 of {@code [A-Za-z0-9._-]}
     * @param type the message type of the topics it reads
     * @param maxPollRecords the most records per poll, 1 to 10,000; overrides the configured default
     * @param <T> the message class
     * @return the consumer; closing it also unbinds its metrics
     * @throws IllegalArgumentException if the group id or the poll size is invalid
     */
    public <T extends MessageLite> KairosConsumer<T> consumer(String groupId, MessageType<T> type, int maxPollRecords) {
        Objects.requireNonNull(type, "type");
        Map<String, Object> config = consumerConfig(groupId, maxPollRecords);
        KafkaConsumer<byte[], Decoded<T>> consumer =
                new KafkaConsumer<>(config, new ByteArrayDeserializer(), type.deserializer());
        KafkaClientMetrics metrics = new KafkaClientMetrics(consumer);
        metrics.bindTo(meterRegistry);
        return new KairosConsumer<>(consumer, metrics);
    }

    /**
     * Creates a consumer of one message type with the configured {@code max.poll.records}.
     *
     * @param groupId the consumer group
     * @param type the message type
     * @param <T> the message class
     * @return the consumer
     */
    public <T extends MessageLite> KairosConsumer<T> consumer(String groupId, MessageType<T> type) {
        return consumer(groupId, type, properties.consumer().maxPollRecords());
    }

    /**
     * Returns the producer configuration.
     *
     * @param name the client name
     * @return a new mutable map
     */
    Map<String, Object> producerConfig(String name) {
        KafkaProperties.Producer producer = properties.producer();
        Map<String, Object> config = commonConfig(name);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, millis(producer.deliveryTimeout()));
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, millis(producer.requestTimeout()));
        config.put(ProducerConfig.LINGER_MS_CONFIG, millis(producer.linger()));
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, producer.batchSizeBytes());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (long) millis(producer.maxBlock()));
        config.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, MAX_REQUEST_BYTES);
        return config;
    }

    /**
     * Returns the consumer configuration.
     *
     * @param groupId the consumer group
     * @param maxPollRecords the most records per poll
     * @return a new mutable map
     */
    Map<String, Object> consumerConfig(String groupId, int maxPollRecords) {
        Objects.requireNonNull(groupId, "groupId");
        if (!GROUP_ID.matcher(groupId).matches()) {
            throw new IllegalArgumentException("A group id is 1 to 128 of [A-Za-z0-9._-]");
        }
        if (maxPollRecords < 1 || maxPollRecords > 10_000) {
            throw new IllegalArgumentException("max.poll.records must be 1 to 10000");
        }
        KafkaProperties.Consumer consumer = properties.consumer();
        Map<String, Object> config = commonConfig(groupId);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, millis(consumer.maxPollInterval()));
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, millis(consumer.sessionTimeout()));
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, consumer.autoOffsetReset());
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // A fetch never needs more than a few maximum-size messages per partition.
        config.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, MAX_REQUEST_BYTES);
        return config;
    }

    private Map<String, Object> commonConfig(String name) {
        Objects.requireNonNull(name, "name");
        if (!GROUP_ID.matcher(name).matches()) {
            throw new IllegalArgumentException("A client name is 1 to 128 of [A-Za-z0-9._-]");
        }
        Map<String, Object> config = new HashMap<>();
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(
                CommonClientConfigs.CLIENT_ID_CONFIG,
                properties.clientIdPrefix() + "-" + name + "-" + clientSequence.incrementAndGet());
        config.put(
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
                properties.securityProtocol().name());
        if (properties.securityProtocol() != KafkaProperties.SecurityProtocol.PLAINTEXT) {
            // Host name verification is Kafka's default; it is set explicitly so no configuration can turn it off.
            config.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
            config.put(SslConfigs.SSL_PROTOCOL_CONFIG, "TLSv1.3");
            config.put(SslConfigs.SSL_ENABLED_PROTOCOLS_CONFIG, "TLSv1.3,TLSv1.2");
            String trustCertificate = properties.trustCertificate();
            if (trustCertificate != null && !trustCertificate.isBlank()) {
                config.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
                config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, trustCertificate);
            }
        }
        if (properties.securityProtocol() == KafkaProperties.SecurityProtocol.SASL_SSL) {
            KafkaProperties.SaslMechanism mechanism = properties.saslMechanism();
            config.put(SaslConfigs.SASL_MECHANISM, mechanism.kafkaName());
            // The values were checked to hold no quote, backslash or control character (KafkaProperties).
            config.put(
                    SaslConfigs.SASL_JAAS_CONFIG,
                    mechanism.loginModule() + " required username=\"" + properties.username() + "\" password=\""
                            + properties.password() + "\";");
        }
        return config;
    }

    private static int millis(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }
}
