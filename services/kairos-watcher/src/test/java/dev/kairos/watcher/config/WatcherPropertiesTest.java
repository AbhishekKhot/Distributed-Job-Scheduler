package dev.kairos.watcher.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WatcherPropertiesTest {
    @Test
    void defaultsMatchLeaseContract() {
        WatcherProperties properties = new WatcherProperties(
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(15),
                Duration.ofSeconds(3),
                2,
                8,
                Duration.ofMillis(200));
        assertThat(properties.leaseTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.shardGroupSize()).isEqualTo(8);
    }

    @Test
    void refusesUnsafeLeaseAndHeartbeatIntervals() {
        assertThatThrownBy(() -> new WatcherProperties(
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(3),
                        2,
                        8,
                        Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WatcherProperties(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(15),
                        2,
                        8,
                        Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
