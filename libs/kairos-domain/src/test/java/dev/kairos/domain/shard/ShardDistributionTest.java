package dev.kairos.domain.shard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShardDistributionTest {

    @Test
    void spreadsNamesEvenlyOverAllShards() {
        UUID teamId = UUID.fromString("0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e");
        int perShard = 200;
        int[] counts = new int[Shard.COUNT];
        for (int i = 0; i < Shard.COUNT * perShard; i++) {
            counts[ShardKey.of(teamId, "job-" + i).value()]++;
        }
        // Binomial with mean 200 and standard deviation of about 14: 6 sigma either side.
        for (int count : counts) {
            assertThat(count).isBetween(perShard - 85, perShard + 85);
        }
    }
}
