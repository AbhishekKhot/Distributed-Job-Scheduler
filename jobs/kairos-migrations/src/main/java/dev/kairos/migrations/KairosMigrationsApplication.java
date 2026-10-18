package dev.kairos.migrations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code kairos-migrations}, a one-shot job run before every release.
 *
 * <p>Applies the Flyway migrations in {@code db/control} to the control database and those in
 * {@code db/cell} to every cell database, then exits.
 */
@SpringBootApplication
public class KairosMigrationsApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(KairosMigrationsApplication.class, args);
    }
}
