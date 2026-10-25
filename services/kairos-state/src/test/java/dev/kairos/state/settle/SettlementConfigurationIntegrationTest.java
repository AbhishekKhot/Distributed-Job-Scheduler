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
@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(
        properties = {
            "management.server.port=0",
            "kairos.state.settle.group-id=t261-config",
            "kairos.state.settle.audit-interval=1h"
        })
class SettlementConfigurationIntegrationTest {
    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry properties) {
        SettlementFixtures.ensureSchema(KairosPostgres.get());
    }

    @Autowired
    ResultConsumer consumer;

    @Autowired
    SettlementBatch settlement;

    @Autowired
    SettlementAuditRelay audit;

    @Autowired
    SettlementProperties properties;

    @Test
    void productionWiringStartsBoundedResultConsumer() {
        assertThat(consumer.isRunning()).isTrue();
        assertThat(settlement).isNotNull();
        assertThat(audit).isNotNull();
        assertThat(properties.maxPollRecords()).isEqualTo(1000);
        consumer.stop();
    }
}
