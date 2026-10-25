package dev.kairos.mocktarget;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the mock target used by integration tests and benchmarks.
 *
 * <p>Simulates team endpoints with configurable latency, status codes, failure rates and
 * idempotency-key tracking, so duplicate deliveries can be counted.
 */
@SpringBootApplication(scanBasePackages = {"dev.kairos.mocktarget.config", "dev.kairos.mocktarget.behavior"})
public class MockTargetApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(MockTargetApplication.class, args);
    }
}
