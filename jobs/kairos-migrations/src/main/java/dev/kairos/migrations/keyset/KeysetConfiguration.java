package dev.kairos.migrations.keyset;

import dev.kairos.migrations.config.MigrationProperties;
import dev.kairos.migrations.schema.DatabaseKind;
import dev.kairos.migrations.schema.MigrationTarget;
import dev.kairos.security.config.PlatformKeysetConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Imports the platform keyset store and bootstrap, backed by the control database.
 *
 * <p>The job does not use the persistence library's pooled data sources, so it supplies the {@code controlJdbcClient}
 * the keyset store needs itself: a client on the control database that connects as the schema owner, like the rest of
 * the job. The key encryption key comes from {@code kairos.security.kek.*} (see {@code application.yml}).
 */
@Configuration(proxyBeanMethods = false)
@Import(PlatformKeysetConfiguration.class)
public class KeysetConfiguration {

    /**
     * The control database's client, used by the platform keyset store.
     *
     * @param properties the job configuration
     * @return a client that opens a new owner connection per use
     */
    @Bean
    JdbcClient controlJdbcClient(MigrationProperties properties) {
        MigrationProperties.Credentials owner = properties.db();
        MigrationTarget control = MigrationTarget.of(
                DatabaseKind.CONTROL, "control", properties.control().db().url(), owner.username(), owner.password());
        return JdbcClient.create(control.dataSource());
    }
}
