package dev.kairos.migrations.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the configuration properties and the clock of the migrations job. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationConfiguration {

    /**
     * The clock that decides which days get partitions. Partitions are UTC days.
     *
     * @return the system clock in UTC
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
