package dev.kairos.state.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.kairos.messaging.client.KairosConsumer;
import dev.kairos.messaging.client.PoisonMessages;
import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.PoisonReason;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.ProtoTimestamps;
import dev.kairos.proto.v1.Result;
import dev.kairos.state.settle.application.SettlementBatch;
import dev.kairos.state.settle.infrastructure.ResultConsumer;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class ResultConsumerTest {
    private final TopicPartition partition = new TopicPartition(KairosTopic.RUN_RESULT.topicName(), 0);
    private final PoisonMessages poison = new PoisonMessages(new SimpleMeterRegistry());
    private final List<Result> accepted = new ArrayList<>();

    private static final Result VALID = createValid();

    static Result valid() {
        return VALID;
    }

    private static Result createValid() {
        return Result.newBuilder()
                .setRunId(ProtoIds.toBytes(TestIds.runId(12).value()))
                .setShard(12)
                .setFence(2)
                .setAttempt(1)
                .setOutcomeValue(1)
                .setHttpStatus(200)
                .setExecutorId("executor-test")
                .setStartedAt(ProtoTimestamps.toTimestamp(Instant.parse("2026-10-25T10:00:00Z")))
                .setFinishedAt(ProtoTimestamps.toTimestamp(Instant.parse("2026-10-25T10:00:01Z")))
                .build();
    }

    private ResultConsumer worker(SettlementBatch settlement) {
        return new ResultConsumer(
                () -> {
                    throw new AssertionError("manual poll");
                },
                settlement,
                poison,
                Duration.ofMillis(10),
                Duration.ofMillis(1));
    }

    private MockConsumer<byte[], Decoded<Result>> consumer() {
        var consumer = new MockConsumer<byte[], Decoded<Result>>("earliest");
        consumer.assign(List.of(partition));
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        return consumer;
    }

    private void add(MockConsumer<byte[], Decoded<Result>> consumer, long offset, Decoded<Result> value) {
        consumer.addRecord(
                new ConsumerRecord<>(partition.topic(), partition.partition(), offset, new byte[] {1}, value));
    }

    @Test
    void skipsWireAndSemanticPoisonAndCommitsAfterSettlement() {
        try (var consumer = consumer()) {
            add(consumer, 0, new Decoded.Poison<>(PoisonReason.MALFORMED, 10));
            add(consumer, 1, new Decoded.Valid<>(Result.getDefaultInstance()));
            add(consumer, 2, new Decoded.Valid<>(valid()));
            assertThat(worker(batch -> {
                                assertThat(consumer.committed(Set.of(partition)).get(partition))
                                        .isNull();
                                accepted.addAll(batch);
                            })
                            .pollOnce(consumer))
                    .isTrue();
            assertThat(accepted).containsExactly(valid());
            assertThat(consumer.committed(Set.of(partition)).get(partition).offset())
                    .isEqualTo(3);
            assertThat(poison.count(KairosTopic.RUN_RESULT, PoisonReason.MALFORMED))
                    .isEqualTo(2);
            assertThat(worker(accepted::addAll).pollOnce(consumer)).isFalse();
        }
    }

    @Test
    void failedCellSettlementRewindsAndDoesNotCommit() {
        try (var consumer = consumer()) {
            add(consumer, 0, new Decoded.Valid<>(valid()));
            assertThatThrownBy(() -> worker(batch -> {
                                throw new IllegalStateException("rollback");
                            })
                            .pollOnce(consumer))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(consumer.position(partition)).isZero();
            assertThat(consumer.committed(Set.of(partition)).get(partition)).isNull();
        }
    }

    @Test
    void failedCommitRewindsDurablySettledBatchForIdempotentReplay() {
        @SuppressWarnings("unchecked")
        var consumer = (org.apache.kafka.clients.consumer.Consumer<byte[], Decoded<Result>>)
                mock(org.apache.kafka.clients.consumer.Consumer.class);
        var record = new ConsumerRecord<byte[], Decoded<Result>>(
                partition.topic(), 0, 7, new byte[] {1}, new Decoded.Valid<>(valid()));
        when(consumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(Map.of(partition, List.of(record)), Map.of()));
        when(consumer.assignment()).thenReturn(Set.of(partition));
        doThrow(new IllegalStateException("commit failed"))
                .when(consumer)
                .commitSync(org.mockito.ArgumentMatchers.<TopicPartition, OffsetAndMetadata>anyMap());
        assertThatThrownBy(() -> worker(accepted::addAll).pollOnce(consumer)).isInstanceOf(IllegalStateException.class);
        verify(consumer).seek(partition, 7);
        assertThat(accepted).containsExactly(valid());
    }

    @Test
    void lifecycleOwnsAndClosesConsumerAndCanRestart() throws Exception {
        CountDownLatch polled = new CountDownLatch(1), closed = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        var consumer = (org.apache.kafka.clients.consumer.Consumer<byte[], Decoded<Result>>)
                mock(org.apache.kafka.clients.consumer.Consumer.class);
        when(consumer.poll(any(Duration.class))).thenAnswer(invocation -> {
            polled.countDown();
            Thread.sleep(5);
            return ConsumerRecords.empty();
        });
        var worker = new ResultConsumer(
                () -> new KairosConsumer<>(consumer, closed::countDown),
                accepted::addAll,
                poison,
                Duration.ofMillis(1),
                Duration.ofMillis(1));
        worker.start();
        worker.start();
        assertThat(polled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.isRunning()).isTrue();
        assertThat(worker.getPhase()).isPositive();
        worker.stop();
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.isRunning()).isFalse();
        verify(consumer).wakeup();
    }
}
