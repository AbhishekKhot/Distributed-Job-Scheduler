package dev.kairos.messaging.config;

import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.KafkaProperties;
import dev.kairos.messaging.client.KafkaPublisher;
import dev.kairos.messaging.client.PoisonMessages;
import dev.kairos.messaging.trace.TraceHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring of the messaging library. Services that use Kafka (the watcher, the executor and the state service)
 * import it explicitly with {@code @Import(MessagingConfiguration.class)}; it is not an auto-configuration, so nothing
 * connects to Kafka unless a service asks for it.
 *
 * <p>Beans: the {@link KafkaClientFactory} (consumers and further producers), one shared {@link KafkaPublisher}
 * (closed on shutdown after its buffered records are sent), the {@link PoisonMessages} handler and the
 * {@link TraceHeaders} helper. Settings come from {@code kairos.kafka.*} ({@link KafkaProperties}).
 *
 * <p>A {@link MeterRegistry} and a Micrometer Tracing {@link Propagator} are used when the service defines them;
 * otherwise a private registry and the no-op propagator are used.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
public class MessagingConfiguration {

    /** Creates the configuration; Spring calls it. */
    public MessagingConfiguration() {}

    /**
     * Creates producers and consumers.
     *
     * @param properties the Kafka settings
     * @param meterRegistry the service's meter registry, if any
     * @return the factory
     */
    @Bean
    public KafkaClientFactory kafkaClientFactory(
            KafkaProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        return new KafkaClientFactory(properties, meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * The service's shared idempotent producer.
     *
     * @param factory the client factory
     * @return the publisher
     */
    @Bean(destroyMethod = "close")
    public KafkaPublisher kafkaPublisher(KafkaClientFactory factory) {
        return factory.publisher("publisher");
    }

    /**
     * Counts, logs and skips records that cannot be decoded.
     *
     * @param meterRegistry the service's meter registry, if any
     * @return the handler
     */
    @Bean
    public PoisonMessages poisonMessages(ObjectProvider<MeterRegistry> meterRegistry) {
        return new PoisonMessages(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Propagates the trace context through record headers.
     *
     * @param propagator the service's propagator, if any
     * @return the helper
     */
    @Bean
    public TraceHeaders traceHeaders(ObjectProvider<Propagator> propagator) {
        return new TraceHeaders(propagator.getIfAvailable(() -> Propagator.NOOP));
    }
}
