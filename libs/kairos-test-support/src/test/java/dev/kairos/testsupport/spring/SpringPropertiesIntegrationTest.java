package dev.kairos.testsupport.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.testsupport.KairosContainers;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.KairosRedis;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Checks that a Spring test gets the connection details of exactly the containers it selects. */
@KairosIntegrationTest(kafka = false)
@SpringJUnitConfig(SpringPropertiesIntegrationTest.EmptyConfiguration.class)
class SpringPropertiesIntegrationTest {

    @Test
    void registersDatasourcePropertiesForControlAndCells(@Autowired Environment environment) throws SQLException {
        KairosPostgres postgres = KairosPostgres.get();

        assertThat(environment.getProperty("kairos.datasource.control.url")).isEqualTo(postgres.controlJdbcUrl());
        assertThat(environment.getProperty("kairos.datasource.control.username")).isEqualTo("sched_app");
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            assertThat(environment.getProperty("kairos.datasource.cells[" + cell + "].url"))
                    .isEqualTo(postgres.cellJdbcUrl(cell))
                    .endsWith("/kairos_cell" + cell);
            assertThat(environment.getProperty("kairos.datasource.cells[" + cell + "].username"))
                    .isEqualTo("sched_app");
        }

        // The registered credentials work on their own, as a service would use them.
        String url = environment.getRequiredProperty(KairosContainers.cellUrl(1));
        String username = environment.getRequiredProperty(KairosContainers.cellUsername(1));
        String password = environment.getRequiredProperty(KairosContainers.cellPassword(1));
        try (Connection connection = DriverManager.getConnection(url, username, password);
                PreparedStatement statement = connection.prepareStatement("SELECT current_database()");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("kairos_cell1");
        }
    }

    @Test
    void registersRedisProperties(@Autowired Environment environment) {
        KairosRedis redis = KairosRedis.get();

        assertThat(environment.getProperty("kairos.redis.host")).isEqualTo(redis.host());
        assertThat(environment.getProperty("kairos.redis.port", Integer.class)).isEqualTo(redis.port());
        assertThat(environment.getProperty("kairos.redis.username")).isEqualTo("kairos");
        assertThat(environment.getProperty("kairos.redis.password")).isEqualTo(redis.password());
    }

    @Test
    void leavesOutContainersThatAreNotSelected(@Autowired Environment environment) {
        assertThat(environment.containsProperty("kairos.kafka.bootstrap-servers")).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {}
}
