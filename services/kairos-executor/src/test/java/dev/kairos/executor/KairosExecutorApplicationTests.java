package dev.kairos.executor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifies that the application context starts with the default configuration. */
@SpringBootTest(
        properties = {
            "management.server.port=0",
            "KAIROS_CONTROL_DB_URL=jdbc:postgresql://localhost:5432/kairos_control",
            "KAIROS_CELL_0_DB_URL=jdbc:postgresql://localhost:5432/kairos_cell0",
            "KAIROS_CELL_1_DB_URL=jdbc:postgresql://localhost:5432/kairos_cell1",
            "KAIROS_DB_USERNAME=sched_app",
            "KAIROS_DB_PASSWORD=test-password",
            "KAIROS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092"
        })
class KairosExecutorApplicationTests {

    @Test
    void contextLoads() {
        // Fails if any bean cannot be created.
    }
}
