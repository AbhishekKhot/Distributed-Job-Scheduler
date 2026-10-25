package dev.kairos.state;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifies that the application context starts with the default configuration. */
@SpringBootTest(properties = {"management.server.port=0", "kairos.state.settle.enabled=false"})
class KairosStateApplicationTests {

    @Test
    void contextLoads() {
        // Fails if any bean cannot be created.
    }
}
