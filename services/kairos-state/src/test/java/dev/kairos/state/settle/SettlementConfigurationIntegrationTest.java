package dev.kairos.state.settle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.state.settle.application.SettlementBatch;
import dev.kairos.state.settle.infrastructure.ResultConsumer;
import dev.kairos.state.settle.infrastructure.SettlementAuditRelay;
import dev.kairos.state.settle.infrastructure.SettlementProperties;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@KairosIntegrationTest(redis = false)
@org.springframework.test.context.ActiveProfiles("local")
@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(
        properties = {
            "management.server.port=0",
            "kairos.state.settle.group-id=t261-config",
            "kairos.state.settle.audit-interval=1h",
            "kairos.state.workflow.reconcile-interval=1h"
        })
class SettlementConfigurationIntegrationTest {
    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry properties) {
        SettlementFixtures.ensureSchema(KairosPostgres.get());
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        properties.add(
                "kairos.security.kek.local-key", () -> java.util.HexFormat.of().formatHex(key));
    }

    @Autowired
    ResultConsumer consumer;

    @Autowired
    SettlementBatch settlement;

    @Autowired
    SettlementAuditRelay audit;

    @Autowired
    SettlementProperties properties;

    @Autowired
    dev.kairos.state.stats.infrastructure.StatsAggregator statistics;

    @Autowired
    dev.kairos.state.stats.infrastructure.StatsProperties statsProperties;

    @Test
    void productionWiringStartsBoundedResultConsumer() {
        assertThat(consumer.isRunning()).isTrue();
        assertThat(settlement).isNotNull();
        assertThat(audit).isNotNull();
        assertThat(properties.maxPollRecords()).isEqualTo(1000);
        assertThat(statistics.isRunning()).isTrue();
        assertThat(statistics.getPhase()).isLessThan(consumer.getPhase());
        assertThat(statsProperties.flushInterval()).isEqualTo(java.time.Duration.ofSeconds(10));
        consumer.stop();
        statistics.stop();
        assertThat(statistics.isRunning()).isFalse();
    }
}
