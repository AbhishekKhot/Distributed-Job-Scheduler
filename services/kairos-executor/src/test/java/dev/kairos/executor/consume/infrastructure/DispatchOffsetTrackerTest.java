package dev.kairos.executor.consume.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class DispatchOffsetTrackerTest {

    private static final TopicPartition PARTITION = new TopicPartition("kairos.run.high", 3);

    @Test
    void onlyDrainsContiguousFinishedOffsetsAndReleasesTheirSlots() {
        DispatchOffsetTracker tracker = new DispatchOffsetTracker();
        long first = tracker.register(PARTITION, 10);
        long second = tracker.register(PARTITION, 11);
        long third = tracker.register(PARTITION, 12);
        tracker.complete(PARTITION, 12, third);

        assertThat(tracker.drainReady().offsets()).isEmpty();

        tracker.complete(PARTITION, 10, first);
        DispatchOffsetTracker.CommitBatch firstBatch = tracker.drainReady();
        assertThat(firstBatch.offsets().get(PARTITION).offset()).isEqualTo(11);
        assertThat(firstBatch.finishedRecords())
                .containsExactly(new DispatchOffsetTracker.RecordRef(PARTITION, 10, first));

        tracker.complete(PARTITION, 11, second);
        DispatchOffsetTracker.CommitBatch rest = tracker.drainReady();
        assertThat(rest.offsets().get(PARTITION).offset()).isEqualTo(13);
        assertThat(rest.finishedRecords())
                .containsExactly(
                        new DispatchOffsetTracker.RecordRef(PARTITION, 11, second),
                        new DispatchOffsetTracker.RecordRef(PARTITION, 12, third));
    }

    @Test
    void revokingAPartitionReleasesCompletedRecordsBehindAGapForReplay() {
        DispatchOffsetTracker tracker = new DispatchOffsetTracker();
        long first = tracker.register(PARTITION, 5);
        long second = tracker.register(PARTITION, 6);
        tracker.complete(PARTITION, 6, second);

        List<DispatchOffsetTracker.RecordRef> discarded = tracker.discard(Set.of(PARTITION));

        assertThat(discarded).containsExactly(new DispatchOffsetTracker.RecordRef(PARTITION, 6, second));
        assertThat(tracker.completeIfTracked(PARTITION, 5, first)).isFalse();
    }

    @Test
    void aLateCompletionCannotCompleteANewRegistrationAtTheSameOffset() {
        DispatchOffsetTracker oldTracker = new DispatchOffsetTracker();
        long oldToken = oldTracker.register(PARTITION, 25);
        oldTracker.discardAll();

        DispatchOffsetTracker newTracker = new DispatchOffsetTracker();
        long newToken = newTracker.register(PARTITION, 25);

        assertThat(newTracker.completeIfTracked(PARTITION, 25, oldToken)).isFalse();
        assertThat(newTracker.completeIfTracked(PARTITION, 25, newToken)).isTrue();
        assertThat(newTracker.drainReady().offsets().get(PARTITION).offset()).isEqualTo(26);
    }
}
