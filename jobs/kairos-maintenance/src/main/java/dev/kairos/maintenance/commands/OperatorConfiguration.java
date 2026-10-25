package dev.kairos.maintenance.commands;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.kairos.maintenance.commands.application.OperatorService;
import dev.kairos.maintenance.commands.infrastructure.JdbcOperatorStore;
import dev.kairos.maintenance.commands.infrastructure.OperatorProperties;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.config.PasswordConfiguration;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.password.PasswordPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Separate DML-only control pool; partition-owner and operator modes are mutually exclusive at entry. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.maintenance.commands.enabled", havingValue = "true")
@EnableConfigurationProperties(OperatorProperties.class)
@Import(PasswordConfiguration.class)
public class OperatorConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource operatorDataSource(OperatorProperties properties) {
        var connection = properties.database();
        var config = new HikariConfig();
        config.setJdbcUrl(connection.url());
        config.setUsername(connection.username());
        config.setPassword(connection.password());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setInitializationFailTimeout(-1);
        config.setPoolName("operator-control");
        return new HikariDataSource(config);
    }

    @Bean
    public OperatorService operatorService(
            HikariDataSource source, PasswordHasher hasher, PasswordPolicy policy, MeterRegistry meters) {
        var database = Database.of(Database.CONTROL, source, DatabaseOptions.defaults(), meters);
        return new OperatorService(
                new JdbcOperatorStore(new ControlTransactions(database), Clock.systemUTC()),
                hasher,
                policy,
                new SecureRandom(),
                Clock.systemUTC());
    }
}
