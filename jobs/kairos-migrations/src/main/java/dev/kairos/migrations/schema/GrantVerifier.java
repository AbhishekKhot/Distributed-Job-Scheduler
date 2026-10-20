package dev.kairos.migrations.schema;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fails the job when the application role lacks DML on any table of schema {@code scheduler}.
 *
 * <p>Tables created by the schema owner get {@code SELECT, INSERT, UPDATE, DELETE} for the application role through
 * the default privileges set by the database init script. A table created another way (by a different role, or after
 * a revoke) would otherwise only surface as a permission error in a running service. Flyway's history table is
 * skipped: the application role has no business there.
 */
public final class GrantVerifier {

    private static final String TABLES_WITHOUT_DML = """
            SELECT c.relname
              FROM pg_catalog.pg_class c
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = :schema
               AND c.relkind IN ('r', 'p')
               AND c.relname <> 'flyway_schema_history'
               AND NOT (pg_catalog.has_table_privilege(:role, c.oid, 'SELECT')
                    AND pg_catalog.has_table_privilege(:role, c.oid, 'INSERT')
                    AND pg_catalog.has_table_privilege(:role, c.oid, 'UPDATE')
                    AND pg_catalog.has_table_privilege(:role, c.oid, 'DELETE'))
             ORDER BY c.relname
            """;

    private final String appRole;

    /**
     * Creates the verifier.
     *
     * @param appRole the role the services connect as
     */
    public GrantVerifier(String appRole) {
        this.appRole = appRole;
    }

    /**
     * Checks every table of the target.
     *
     * @param target the database
     * @throws IllegalStateException naming the tables the application role cannot use
     */
    public void verify(MigrationTarget target) {
        List<String> missing = JdbcClient.create(target.dataSource())
                .sql(TABLES_WITHOUT_DML)
                .param("schema", SchemaMigrator.SCHEMA)
                .param("role", appRole)
                .query(String.class)
                .list();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Role " + appRole + " lacks SELECT, INSERT, UPDATE or DELETE on "
                    + String.join(", ", missing) + " in " + target.name());
        }
    }
}
