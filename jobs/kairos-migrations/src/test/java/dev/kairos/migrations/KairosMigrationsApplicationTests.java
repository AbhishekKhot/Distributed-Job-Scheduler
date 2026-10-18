package dev.kairos.migrations;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifies that the application context starts with the default configuration. */
@SpringBootTest(properties = "management.server.port=0")
class KairosMigrationsApplicationTests {

    @Test
    void contextLoads() {
        // Fails if any bean cannot be created.
    }
}
