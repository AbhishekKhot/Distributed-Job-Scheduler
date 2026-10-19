package dev.kairos.migrations.schema;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the Flyway migrations of one database.
 *
 * <p>Flyway takes a PostgreSQL advisory lock for the duration of a migration, so two copies of the job started at the
 * same time apply each script once. {@code clean} is disabled: the job can never drop a schema. Scripts are validated
 * against the history table, so an edited script that has already run fails the job instead of being skipped.
 */
public final class SchemaMigrator {

    /** The schema every Kairos table lives in; created by the database init script, not by Flyway. */
    public static final String SCHEMA = "scheduler";

    private final String appRole;

    /**
     * Creates a migrator.
     *
     * @param appRole the role the services connect as, available to scripts as the {@code appRole} placeholder
     */
    public SchemaMigrator(String appRole) {
        this.appRole = appRole;
    }

    /**
     * Applies every pending migration of the target's kind.
     *
     * @param target the database
     * @return the number of migrations applied, 0 when the schema was already current
     * @throws org.flywaydb.core.api.FlywayException if a migration or the validation fails
     */
    public int migrate(MigrationTarget target) {
        MigrateResult result = Flyway.configure()
                .dataSource(target.dataSource())
                .locations(target.kind().location())
                .schemas(SCHEMA)
                .createSchemas(false)
                .placeholders(Map.of("appRole", appRole))
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .failOnMissingLocations(true)
                .load()
                .migrate();
        return result.migrationsExecuted;
    }
}
