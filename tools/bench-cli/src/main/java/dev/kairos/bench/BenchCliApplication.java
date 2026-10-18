package dev.kairos.bench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the benchmark CLI.
 *
 * <p>Creates jobs at a target rate, drives the load scenarios and verifies afterwards that no
 * run was lost and none was executed twice.
 */
@SpringBootApplication
public class BenchCliApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(BenchCliApplication.class, args);
    }
}
