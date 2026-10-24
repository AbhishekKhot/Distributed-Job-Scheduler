package dev.kairos.executor;

import dev.kairos.security.token.AccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Verifies that the application context starts with the default configuration. */
@SpringBootTest(
        properties = {
            "management.server.port=0",
            "kairos.executor.consumer.enabled=false",
            "kairos.security.kek.local-key=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "KAIROS_CONTROL_DB_URL=jdbc:postgresql://localhost:5432/kairos_control",
            "KAIROS_CELL_0_DB_URL=jdbc:postgresql://localhost:5432/kairos_cell0",
            "KAIROS_CELL_1_DB_URL=jdbc:postgresql://localhost:5432/kairos_cell1",
            "KAIROS_DB_USERNAME=sched_app",
            "KAIROS_DB_PASSWORD=test-password",
            "KAIROS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092",
            "KAIROS_REDIS_HOST=localhost",
            "KAIROS_REDIS_PORT=6379",
            "KAIROS_REDIS_USERNAME=kairos",
            "KAIROS_REDIS_PASSWORD=test-password"
        })
@ActiveProfiles("local")
class KairosExecutorApplicationTests {

    @MockitoBean(name = "executorInternalAccessTokens")
    private AccessTokenService internalTokens;

    @Test
    void contextLoads() {
        // Fails if any bean cannot be created.
    }
}
