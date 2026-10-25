package dev.kairos.state.settle;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.retry.RetryPlanner;
import dev.kairos.engine.outbox.OutboxConfiguration;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.PoisonMessages;
import dev.kairos.messaging.config.MessagingConfiguration;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.state.settle.application.ResultDecider;
import dev.kairos.state.settle.application.SettlementBatch;
import dev.kairos.state.settle.application.WorkflowResultHandler;
import dev.kairos.state.settle.infrastructure.JdbcResultSettler;
import dev.kairos.state.settle.infrastructure.ResultConsumer;
import dev.kairos.state.settle.infrastructure.SettlementAuditRelay;
import dev.kairos.state.settle.infrastructure.SettlementPolicyLoader;
import dev.kairos.state.settle.infrastructure.SettlementProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/** Production wiring for T-261; T-262 and T-263 supply optional transactional and after-commit hooks. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.state.settle.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SettlementProperties.class)
@EnableScheduling
@Import({OutboxConfiguration.class, MessagingConfiguration.class})
public class SettlementConfiguration {
    @Bean
    public Clock settlementClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SettlementBatch resultSettler(
            CellRouter cells,
            @Qualifier("controlJdbcClient") JdbcClient control,
            ObjectProvider<WorkflowResultHandler> workflows,
            ObjectProvider<SettlementBatch.Observer> observers,
            OutboxWriter outbox,
            KairosIdFactory ids,
            Clock settlementClock,
            JsonMapper json,
            MeterRegistry meters) {
        return new JdbcResultSettler(
                cells,
                new SettlementPolicyLoader(control, json),
                new ResultDecider(settlementClock, new RetryPlanner(settlementClock, ThreadLocalRandom::current)),
                workflows.getIfAvailable(WorkflowResultHandler::pending),
                observers.orderedStream().toList(),
                outbox,
                ids,
                settlementClock,
                json,
                meters);
    }

    @Bean
    public ResultConsumer resultConsumer(
            KafkaClientFactory factory,
            SettlementBatch settler,
            PoisonMessages poison,
            SettlementProperties properties) {
        return new ResultConsumer(
                () -> factory.consumer(properties.groupId(), MessageType.RESULT, properties.maxPollRecords()),
                settler,
                poison,
                properties.pollTimeout(),
                properties.retryDelay());
    }

    @Bean
    public SettlementAuditRelay settlementAuditRelay(
            CellRouter cells, @Qualifier("controlJdbcClient") JdbcClient control) {
        return new SettlementAuditRelay(cells, control);
    }
}
