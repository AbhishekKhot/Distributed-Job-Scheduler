package dev.kairos.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.shard.Shard;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TestIdsTest {

    @Test
    void shardedIdsCarryTheChosenShard() {
        assertThat(KairosId.shardOf(TestIds.id(0))).isEqualTo(Shard.of(0));
        assertThat(KairosId.shardOf(TestIds.jobId(7).value())).isEqualTo(Shard.of(7));
        assertThat(KairosId.shardOf(TestIds.workflowId(128).value())).isEqualTo(Shard.of(128));
        assertThat(KairosId.shardOf(TestIds.runId(200).value())).isEqualTo(Shard.of(200));
        assertThat(KairosId.shardOf(TestIds.workflowRunId(255).value())).isEqualTo(Shard.of(255));
        assertThat(KairosId.shardOf(TestIds.dlqId(42).value())).isEqualTo(Shard.of(42));
    }

    @Test
    void unshardedIdsAreValidKairosIds() {
        assertThat(KairosId.isKairosId(TestIds.teamId().value())).isTrue();
        assertThat(KairosId.isKairosId(TestIds.userId().value())).isTrue();
        assertThat(KairosId.isKairosId(TestIds.endpointId().value())).isTrue();
    }

    @Test
    void idsAreUnique() {
        assertThat(TestIds.runId(3)).isNotEqualTo(TestIds.runId(3));
    }

    @Test
    void rejectsShardsOutOfRange() {
        assertThatThrownBy(() -> TestIds.id(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestIds.runId(256)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factoryOnAMutableClockControlsTheTimestamp() {
        Instant start = Instant.parse("2026-10-20T10:00:00Z");
        MutableClock clock = MutableClock.at(start);
        KairosIdFactory factory = TestIds.factory(clock);

        UUID first = factory.newId(Shard.of(9));
        clock.advance(Duration.ofSeconds(5));
        UUID second = factory.newId(Shard.of(9));

        assertThat(KairosId.timestampOf(first)).isEqualTo(start);
        assertThat(KairosId.timestampOf(second)).isEqualTo(start.plusSeconds(5));
        assertThat(first).isLessThan(second);
    }
}
