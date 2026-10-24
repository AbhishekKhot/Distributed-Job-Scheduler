package dev.kairos.executor.consume.infrastructure;

import dev.kairos.executor.consume.application.DispatchAdmission;
import dev.kairos.executor.consume.application.DispatchProcessor;
import dev.kairos.executor.consume.application.LocalDispatchLimiter;
import dev.kairos.executor.gate.application.ExecutionAction;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.PoisonMessages;
import dev.kairos.messaging.config.MessagingConfiguration;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.config.PersistenceConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Explicit persistence and Kafka wiring for executor dispatch consumption. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutorConsumerProperties.class)
@Import({PersistenceConfiguration.class, MessagingConfiguration.class})
public class ExecutorConsumerConfiguration {

    /** Shared UTC clock for dispatch deferral timestamps. */
    @Bean
    public Clock executorClock() {
        return Clock.systemUTC();
    }

    /** Pod-wide hard capacity shared by all priority tiers. */
    @Bean
    public DispatchCapacity dispatchCapacity(ExecutorConsumerProperties properties) {
        return new DispatchCapacity(properties.highWater());
    }

    /** Bind executor flow-control and poll metrics. */
    @Bean
    public ExecutorConsumerMetrics executorConsumerMetrics(MeterRegistry meters, DispatchCapacity capacity) {
        return new ExecutorConsumerMetrics(meters, capacity);
    }

    /** Local per-team and per-endpoint admission counters. */
    @Bean
    public LocalDispatchLimiter localDispatchLimiter() {
        return new LocalDispatchLimiter();
    }

    /** Control and cell database adapter for concurrency limits and durable deferral. */
    @Bean
    public DispatchAdmission dispatchAdmission(
            @Qualifier("controlJdbcClient") JdbcClient control,
            CellRouter cells,
            LocalDispatchLimiter limiter,
            ExecutorConsumerProperties properties,
            Clock executorClock,
            MeterRegistry meters) {
        return new JdbcDispatchAdmission(control, cells, limiter, properties, executorClock, meters);
    }

    /** Starts consumers once T-243 supplies the action that publishes durable execution outcomes. */
    @Bean
    @ConditionalOnBean(ExecutionAction.class)
    public DispatchConsumerManager dispatchConsumerManager(
            KafkaClientFactory factory,
            DispatchProcessor processor,
            DispatchAdmission admission,
            PoisonMessages poisonMessages,
            DispatchCapacity capacity,
            ExecutorConsumerProperties properties,
            ExecutorConsumerMetrics metrics) {
        return new DispatchConsumerManager(
                factory, processor, admission, poisonMessages, capacity, properties, metrics);
    }
}
