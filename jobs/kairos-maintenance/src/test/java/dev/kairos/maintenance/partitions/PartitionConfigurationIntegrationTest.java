package dev.kairos.maintenance.partitions;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.maintenance.partitions.application.PartitionManager;
import dev.kairos.testsupport.KairosPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "management.server.port=0")
@org.springframework.test.annotation.DirtiesContext
class PartitionConfigurationIntegrationTest {
    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        KairosPostgres postgres = KairosPostgres.get();
        PartitionFixtures.ensureSchema(postgres);
        registry.add("kairos.datasource.control.url", postgres::controlJdbcUrl);
        registry.add("kairos.datasource.control.username", postgres::ownerUsername);
        registry.add("kairos.datasource.control.password", postgres::ownerPassword);
        for (int i = 0; i < 2; i++) {
            int cell = i;
            String prefix = "kairos.datasource.cells[" + cell + "].";
            registry.add(prefix + "url", () -> postgres.cellJdbcUrl(cell));
            registry.add(prefix + "username", postgres::ownerUsername);
            registry.add(prefix + "password", postgres::ownerPassword);
        }
    }

    @Autowired
    PartitionManager manager;

    @Test
    void productionWiringRunsAgainstOwnerConnectionsAndClosesPools() {
        assertThat(manager.run()).hasSize(3);
    }
}
