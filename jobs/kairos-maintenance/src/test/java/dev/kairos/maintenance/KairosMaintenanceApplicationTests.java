package dev.kairos.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifies that the application context starts with the default configuration. */
@SpringBootTest(properties = {"management.server.port=0", "kairos.maintenance.partitions.enabled=false"})
class KairosMaintenanceApplicationTests {

    @Test
    void contextLoads() {
        // Fails if any bean cannot be created.
    }
}
