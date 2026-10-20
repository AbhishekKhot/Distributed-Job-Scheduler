package dev.kairos.messaging.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** Pause at the high water mark, resume at the low one, and re-pause partitions a rebalance adds. */
class PartitionFlowControlTest {

    private static final TopicPartition P0 = new TopicPartition("kairos.run.high", 0);
    private static final TopicPartition P1 = new TopicPartition("kairos.run.high", 1);
    private static final TopicPartition P2 = new TopicPartition("kairos.run.high", 2);

    private final MockConsumer<byte[], byte[]> consumer = new MockConsumer<>("earliest");

    @Test
    void pausesAtTheHighWaterMarkAndResumesAtTheLowOne() {
        consumer.assign(List.of(P0, P1));
        PartitionFlowControl control = new PartitionFlowControl(10, 4);

        control.started(9);
        assertThat(control.apply(consumer)).isFalse();
        assertThat(consumer.paused()).isEmpty();

        control.started(1);
        assertThat(control.apply(consumer)).isTrue();
        assertThat(consumer.paused()).containsExactlyInAnyOrder(P0, P1);

        control.finished(5);
        assertThat(control.apply(consumer)).isTrue();
        assertThat(control.inFlight()).isEqualTo(5);

        control.finished(1);
        assertThat(control.apply(consumer)).isFalse();
        assertThat(control.paused()).isFalse();
        assertThat(consumer.paused()).isEmpty();
    }

    @Test
    void pausesPartitionsAssignedWhilePaused() {
        consumer.assign(List.of(P0));
        PartitionFlowControl control = new PartitionFlowControl(2, 0);
        control.started(2);
        control.apply(consumer);

        consumer.assign(List.of(P0, P1, P2));
        control.apply(consumer);

        assertThat(consumer.paused()).isEqualTo(Set.of(P0, P1, P2));
    }

    @Test
    void worksWithoutAssignedPartitions() {
        PartitionFlowControl control = new PartitionFlowControl(1, 0);
        control.started(1);
        assertThat(control.apply(consumer)).isTrue();
        control.finished(1);
        assertThat(control.apply(consumer)).isFalse();
    }

    @Test
    void rejectsInvalidMarksAndCounts() {
        assertThatThrownBy(() -> new PartitionFlowControl(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionFlowControl(5, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionFlowControl(5, -1)).isInstanceOf(IllegalArgumentException.class);
        PartitionFlowControl control = new PartitionFlowControl(5, 1);
        assertThatThrownBy(() -> control.started(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.finished(1)).isInstanceOf(IllegalStateException.class);
        assertThat(control.inFlight()).isZero();
    }
}
