package dev.kairos.maintenance.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.maintenance.commands.application.OperatorService;
import dev.kairos.maintenance.partitions.PartitionFixtures;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.testsupport.KairosPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        properties = {"kairos.maintenance.partitions.enabled=false", "kairos.maintenance.commands.enabled=true"})
@DirtiesContext
class OperatorConfigurationIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var postgres = KairosPostgres.get();
        PartitionFixtures.ensureSchema(postgres);
        properties.add("kairos.maintenance.commands.database.url", postgres::controlJdbcUrl);
        properties.add("kairos.maintenance.commands.database.username", postgres::appUsername);
        properties.add("kairos.maintenance.commands.database.password", postgres::appPassword);
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    OperatorService service;

    @Autowired
    com.zaxxer.hikari.HikariDataSource source;

    @Test
    void operatorWiringRequiresOnlyControlDmlCredentialsAndOwnsOneClosingPool() throws Exception {
        assertThat(service).isNotNull();
        assertThat(context.getBeansOfType(DataSourceRegistry.class)).isEmpty();
        assertThat(source.getMaximumPoolSize()).isEqualTo(1);
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT current_user")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("sched_app");
        }
    }
}
