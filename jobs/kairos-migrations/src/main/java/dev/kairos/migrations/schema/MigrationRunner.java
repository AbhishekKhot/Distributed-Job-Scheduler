package dev.kairos.migrations.schema;

import dev.kairos.migrations.config.MigrationProperties;
import dev.kairos.security.keyset.PlatformKeysetBootstrap;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the whole job once at startup: the control database first, then every cell in order.
 *
 * <p>For each database it applies the pending migrations, creates the daily partitions and verifies that the
 * application role can use every table. The control database also gets its {@code cell_map} seeded. Once every
 * database is migrated, the platform access-token signing keyset is created in the control database if it is missing
 * (an existing keyset is never replaced; rotation is an operator action). Every step is idempotent and safe when two
 * copies of the job run at once, so the job can run before every release. Any failure propagates out of {@link #run},
 * which stops the application with a non-zero exit code before the services that depend on the job start.
 */
@Component
public class MigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final MigrationProperties properties;
    private final SchemaMigrator migrator;
    private final PartitionCreator partitions;
    private final GrantVerifier grants;
    private final CellMapSeeder cellMap;
    private final PlatformKeysetBootstrap keysets;

    /**
     * Creates the runner.
     *
     * @param properties the job configuration
     * @param clock decides which days get partitions
     * @param keysets creates the platform keysets the API needs
     */
    public MigrationRunner(MigrationProperties properties, Clock clock, PlatformKeysetBootstrap keysets) {
        this.properties = properties;
        String appRole = properties.migrations().appRole();
        this.migrator = new SchemaMigrator(appRole);
        this.partitions = new PartitionCreator(clock, properties.migrations().partitionDaysAhead());
        this.grants = new GrantVerifier(appRole);
        this.cellMap = new CellMapSeeder();
        this.keysets = keysets;
    }

    @Override
    public void run(ApplicationArguments args) {
        MigrationTarget control = control();
        migrate(control);
        cellMap.seed(control, properties.cell().size());
        for (MigrationTarget cell : cells()) {
            migrate(cell);
        }
        log.info("Migrated the control database and {} cells", properties.cell().size());
        // The keyset table exists only once the control schema is migrated. Only the outcome is logged, never the
        // keyset, which is sealed by the key encryption key in any case.
        if (keysets.ensureJwtSigningKeyset()) {
            log.info("Created the platform access-token signing keyset");
        } else {
            log.info("Platform access-token signing keyset already present; left unchanged");
        }
    }

    /**
     * Returns the control database target.
     *
     * @return the target, connecting as the schema owner
     */
    public MigrationTarget control() {
        MigrationProperties.Credentials owner = properties.db();
        return MigrationTarget.of(
                DatabaseKind.CONTROL, "control", properties.control().db().url(), owner.username(), owner.password());
    }

    /**
     * Returns the cell database targets, in cell order.
     *
     * @return the targets, connecting as the schema owner
     */
    public List<MigrationTarget> cells() {
        MigrationProperties.Credentials owner = properties.db();
        List<MigrationTarget> cells = new ArrayList<>();
        for (int cell = 0; cell < properties.cell().size(); cell++) {
            cells.add(MigrationTarget.of(
                    DatabaseKind.CELL,
                    "cell " + cell,
                    properties.cell().get(cell).db().url(),
                    owner.username(),
                    owner.password()));
        }
        return List.copyOf(cells);
    }

    private void migrate(MigrationTarget target) {
        int applied = migrator.migrate(target);
        List<String> created = partitions.createPartitions(target);
        grants.verify(target);
        log.info(
                "Database {}: applied {} migrations, {} daily partitions present, grants verified",
                target.name(),
                applied,
                created.size());
    }
}
