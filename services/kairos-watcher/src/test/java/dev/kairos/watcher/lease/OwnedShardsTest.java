package dev.kairos.watcher.lease;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnedShardsTest {
    @Test
    void snapshotsAreSortedAndStopWorkBeforeLeaseExpiry() {
        OwnedShards owned = new OwnedShards();
        Instant until = Instant.parse("2026-01-01T00:00:15Z");
        OwnedShards.Lease high = new OwnedShards.Lease(9, 2, until, until.minusSeconds(3));
        OwnedShards.Lease low = new OwnedShards.Lease(1, 3, until, until.minusSeconds(3));
        owned.replace(4, List.of(high, low));

        assertThat(owned.inCell(4)).containsExactly(low, high);
        assertThat(owned.mayStart(4, 1, 3, until.minusSeconds(4))).isTrue();
        assertThat(owned.mayStart(4, 1, 2, until.minusSeconds(4))).isFalse();
        assertThat(owned.mayStart(4, 1, 3, until.minusSeconds(3))).isFalse();
        assertThat(owned.snapshot()).containsKey(4);
    }
}
