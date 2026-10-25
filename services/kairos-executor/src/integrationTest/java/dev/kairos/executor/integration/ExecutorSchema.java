package dev.kairos.executor.integration;

import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.RepositoryFiles;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Applies the production SQL scripts before the full watcher context starts. */
final class ExecutorSchema {
    private static boolean ready;

    private ExecutorSchema() {}

    static synchronized void ensure(KairosPostgres postgres) {
        if (ready) return;
        migrate(postgres.controlOwnerDataSource(), "control", postgres.appUsername());
        JdbcClient control = JdbcClient.create(postgres.controlDataSource());
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            migrate(postgres.cellOwnerDataSource(cell), "cell", postgres.appUsername());
            control.sql("""
                    INSERT INTO scheduler.cell_map(cell_id,shard_from,shard_to,datasource_ref,status)
                    VALUES (:cell,:lo,:hi,:ref,'ACTIVE')
                    """)
                    .param("cell", cell)
                    .param("lo", cell * 128)
                    .param("hi", cell * 128 + 127)
                    .param("ref", "cell-" + cell)
                    .update();
        }
        ready = true;
    }

    private static void migrate(DataSource owner, String directory, String role) {
        try (var paths = Files.list(RepositoryFiles.resolve(
                                "jobs/kairos-migrations/src/main/resources/db/" + directory + "/afterMigrate.sql")
                        .getParent());
                var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            for (var path : paths.filter(p -> p.getFileName().toString().startsWith("V")
                            && p.toString().endsWith(".sql"))
                    .sorted()
                    .toList()) statement.execute(Files.readString(path).replace("${appRole}", role));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not apply executor integration schema", failure);
        }
    }
}
