package dev.kairos.messaging.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.messaging.config.MessagingConfiguration;
import dev.kairos.messaging.trace.TraceHeaders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The Spring configuration, property binding and validation, and the client settings of IMPLEMENTATION_CHECKLIST
 * section 4.7. No broker is needed: Kafka clients connect lazily.
 */
class KafkaClientFactoryTest {

    /** A password generated for every run, so no credential-like literal sits in the test. */
    private static final String PASSWORD = randomHex();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MessagingConfiguration.class)
            .withPropertyValues("kairos.kafka.bootstrap-servers=127.0.0.1:1");

    @Test
    void wiresTheBeansWithTheDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KafkaClientFactory.class);
            assertThat(context).hasSingleBean(KafkaPublisher.class);
            assertThat(context).hasSingleBean(PoisonMessages.class);
            assertThat(context).hasSingleBean(TraceHeaders.class);
            KafkaProperties properties = context.getBean(KafkaProperties.class);
            assertThat(properties.securityProtocol()).isEqualTo(KafkaProperties.SecurityProtocol.PLAINTEXT);
            assertThat(properties.producer().deliveryTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.consumer().maxPollRecords()).isEqualTo(500);
            assertThat(properties).isEqualTo(KafkaProperties.plaintext("127.0.0.1:1"));
        });
    }

    @Test
    void producersFollowTheMessagingStandard() {
        KafkaClientFactory factory = factory(KafkaProperties.plaintext("kafka:9092"));
        Map<String, Object> config = factory.producerConfig("outbox-relay");

        assertThat(config)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000)
                .containsEntry(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, KafkaClientFactory.MAX_REQUEST_BYTES)
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
                .doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);
        assertThat((String) config.get(CommonClientConfigs.CLIENT_ID_CONFIG)).startsWith("kairos-outbox-relay-");
    }

    @Test
    void consumersCommitManuallyWithTheCooperativeStickyAssignor() {
        KafkaClientFactory factory = factory(KafkaProperties.plaintext("kafka:9092"));
        Map<String, Object> config = factory.consumerConfig("kairos-executor", 250);

        assertThat(config)
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "kairos-executor")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(
                        ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 250)
                .containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        assertThatThrownBy(() -> factory.consumerConfig("bad group", 250)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.consumerConfig("group", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.consumerConfig("group", 10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saslOverTlsVerifiesTheBrokerAndKeepsThePasswordOutOfToString(@TempDir Path directory) throws IOException {
        Path certificate = Files.writeString(directory.resolve("kafka-ca.pem"), "placeholder");
        // Properties only: a producer would load the placeholder certificate and fail.
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(
                        "kairos.kafka.bootstrap-servers=kafka-1.internal:9092,kafka-2.internal:9092",
                        "kairos.kafka.security-protocol=SASL_SSL",
                        "kairos.kafka.sasl-mechanism=SCRAM_SHA_512",
                        "kairos.kafka.username=kairos-executor",
                        "kairos.kafka.password=" + PASSWORD,
                        "kairos.kafka.trust-certificate=" + certificate)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KafkaProperties properties = context.getBean(KafkaProperties.class);
                    assertThat(properties.toString()).doesNotContain(PASSWORD).contains("password=<redacted>");

                    Map<String, Object> config = factory(properties).producerConfig("publisher");
                    assertThat(config)
                            .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                            .containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512")
                            .containsEntry(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https")
                            .containsEntry(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM")
                            .containsEntry(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, certificate.toString());
                    assertThat((String) config.get(SaslConfigs.SASL_JAAS_CONFIG))
                            .startsWith("org.apache.kafka.common.security.scram.ScramLoginModule required ")
                            .contains("username=\"kairos-executor\"");
                });
        runner.withPropertyValues("kairos.kafka.trust-certificate=" + directory.resolve("missing.pem"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void tlsWithoutSaslUsesTheJdkTrustStore() {
        runner.withPropertyValues("kairos.kafka.security-protocol=SSL").run(context -> {
            Map<String, Object> config =
                    factory(context.getBean(KafkaProperties.class)).consumerConfig("group", 10);
            assertThat(config)
                    .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
                    .doesNotContainKey(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG)
                    .doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);
        });
    }

    @Test
    void rejectsInvalidSettings() {
        runner.withPropertyValues("kairos.kafka.bootstrap-servers=kafka")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.kafka.bootstrap-servers=")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.kafka.security-protocol=SASL_PLAINTEXT")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.kafka.security-protocol=SASL_SSL")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "kairos.kafka.security-protocol=SASL_SSL",
                        "kairos.kafka.username=kairos",
                        "kairos.kafka.password=" + PASSWORD + "\"; injected=\"")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.kafka.consumer.max-poll-records=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.kafka.consumer.auto-offset-reset=none")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.kafka.producer.delivery-timeout=1h")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "kairos.kafka.producer.delivery-timeout=6s", "kairos.kafka.producer.request-timeout=10s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.kafka.consumer.session-timeout=1s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.kafka.client-id-prefix=bad prefix")
                .run(context -> assertThat(context).hasFailed());
    }

    /** Binds the properties without creating any client. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaProperties.class)
    static class PropertiesOnly {}

    private static KafkaClientFactory factory(KafkaProperties properties) {
        return new KafkaClientFactory(properties, new SimpleMeterRegistry());
    }

    private static String randomHex() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
