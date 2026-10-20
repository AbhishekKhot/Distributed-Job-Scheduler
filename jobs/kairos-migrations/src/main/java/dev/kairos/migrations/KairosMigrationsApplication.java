package dev.kairos.migrations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code kairos-migrations}, a one-shot job run before every release.
 *
 * <p>Applies the Flyway migrations in {@code db/control} to the control database and those in
 * {@code db/cell} to every cell database, then exits. The exit code is 0 when every database was
 * migrated and verified; any failure stops the job with a non-zero code.
 */
@SpringBootApplication
public class KairosMigrationsApplication {

    /**
     * Runs the job and exits with its status.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        // A failed migration throws out of run(), so only a completed job reaches exit(); the
        // explicit exit makes sure no non-daemon thread keeps the container alive afterwards.
        System.exit(SpringApplication.exit(SpringApplication.run(KairosMigrationsApplication.class, args)));
    }
}
