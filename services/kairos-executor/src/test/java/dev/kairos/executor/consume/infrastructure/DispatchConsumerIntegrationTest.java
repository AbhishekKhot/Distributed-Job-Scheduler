package dev.kairos.executor.consume.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import dev.kairos.domain.id.RunId;
import dev.kairos.executor.consume.application.DispatchAdmission;
import dev.kairos.executor.consume.application.DispatchProcessor;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.KafkaProperties;
import dev.kairos.messaging.client.KafkaPublisher;
import dev.kairos.messaging.client.KairosConsumer;
import dev.kairos.messaging.client.OutgoingRecord;
import dev.kairos.messaging.client.PartitionFlowControl;
import dev.kairos.messaging.client.PoisonMessages;
import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.TargetType;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosKafka;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flood back-pressure and offset replay using the real Kafka broker and executor consumer loop. */
@KairosIntegrationTest(postgres = false, redis = false)
class DispatchConsumerIntegrationTest {

    private static final int CAPACITY = 4_000;
    private static final int TOTAL = 5_000;
    private static final int BLOCKED_WINDOW = 2_000;
    private static final Duration WAIT = Duration.ofSeconds(90);

    private SimpleMeterRegistry meters;
    private KafkaClientFactory factory;
    private KafkaPublisher publisher;

    @BeforeEach
    void setUp(KairosKafka kafka) {
        meters = new SimpleMeterRegistry();
        factory = new KafkaClientFactory(KafkaProperties.plaintext(kafka.bootstrapServers()), meters);
        publisher = factory.publisher("t241-test");
    }

    @AfterEach
    void tearDown() {
        publisher.close();
    }

    @Test
    void aBlockedPartitionGapKeepsMemoryAndInFlightBoundedUntilTheGapCloses() throws Exception {
        String groupPrefix = groupPrefix();
        UUID teamId = TestIds.teamId().value();
        UUID endpointId = TestIds.endpointId().value();
        UUID targetId = TestIds.jobId(7).value();
        List<Dispatch> dispatches = flood(teamId, endpointId, targetId);
        List<org.apache.kafka.clients.producer.RecordMetadata> metadata = publish(dispatches);
        Map<TopicPartition, List<Integer>> offsetOrder = new HashMap<>();
        for (int i = 0; i < TOTAL; i++) {
            var record = metadata.get(i);
            offsetOrder
                    .computeIfAbsent(new TopicPartition(record.topic(), record.partition()), _ -> new ArrayList<>())
                    .add(i);
        }
        Set<Integer> gapIndexes = new java.util.HashSet<>();
        List<Integer> earlyOrder = new ArrayList<>();
        offsetOrder.values().forEach(indices -> {
            indices.sort(java.util.Comparator.comparingLong(
                    index -> metadata.get(index).offset()));
            gapIndexes.add(indices.getFirst());
            earlyOrder.addAll(indices.subList(1, indices.size()));
        });
        Set<Integer> earlyIndexes = Set.copyOf(earlyOrder.subList(0, 1_000));
        Map<UUID, Integer> indexes = new HashMap<>();
        CountDownLatch[] gates = new CountDownLatch[TOTAL];
        for (int i = 0; i < TOTAL; i++) {
            indexes.put(uuid(dispatches.get(i).getRunId()), i);
            gates[i] = new CountDownLatch(1);
        }
        CountDownLatch earlyFinished = new CountDownLatch(1_000);
        CountDownLatch firstFinished = new CountDownLatch(gapIndexes.size());
        CountDownLatch allStarted = new CountDownLatch(TOTAL);
        DispatchCapacity capacity = new DispatchCapacity(CAPACITY);
        AtomicBoolean sample = new AtomicBoolean(true);
        AtomicInteger observedCapacity = new AtomicInteger();
        Thread sampler = Thread.ofPlatform().start(() -> {
            while (sample.get()) {
                observedCapacity.accumulateAndGet(capacity.inFlight(), Math::max);
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
        });
        DispatchProcessor processor = dispatch -> {
            int index = indexes.get(uuid(dispatch.getRunId()));
            allStarted.countDown();
            gates[index].await();
            if (gapIndexes.contains(index)) firstFinished.countDown();
            if (earlyIndexes.contains(index)) earlyFinished.countDown();
        };
        DispatchConsumerRunner runner = runner(groupPrefix, processor, capacity);

        try {
            runner.start();

            assertThat(awaitCount(allStarted, TOTAL - BLOCKED_WINDOW, WAIT)).isTrue();
            int blockedWindow = capacity.inFlight();
            assertThat(blockedWindow).isBetween(BLOCKED_WINDOW, CAPACITY);
            earlyIndexes.forEach(index -> gates[index].countDown());
            assertThat(await(earlyFinished, WAIT)).isTrue();
            Thread.sleep(2_000);
            assertThat(allStarted.getCount()).isGreaterThanOrEqualTo(TOTAL - CAPACITY);
            assertThat(capacity.inFlight()).isBetween(blockedWindow, CAPACITY);

            gapIndexes.forEach(index -> gates[index].countDown());
            assertThat(await(firstFinished, WAIT)).isTrue();
            assertThat(observedCapacity.get()).isLessThanOrEqualTo(CAPACITY);
        } finally {
            for (CountDownLatch gate : gates) {
                gate.countDown();
            }
            runner.stop();
            runner.join(Duration.ofSeconds(30));
            sample.set(false);
            sampler.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void restartReplaysOnlyTheRecordAfterTheLastContiguousCommit() throws Exception {
        String groupPrefix = groupPrefix();
        UUID teamId = TestIds.teamId().value();
        UUID endpointId = TestIds.endpointId().value();
        UUID targetId = TestIds.jobId(9).value();
        Dispatch first = dispatch(TestIds.runId(9), teamId, endpointId, targetId);
        Dispatch unfinished = dispatch(TestIds.runId(9), teamId, endpointId, targetId);
        List<org.apache.kafka.clients.producer.RecordMetadata> metadata = publish(List.of(first, unfinished));
        assertThat(metadata.get(0).partition()).isEqualTo(metadata.get(1).partition());

        CountDownLatch unfinishedEntered = new CountDownLatch(1);
        CountDownLatch releaseUnfinished = new CountDownLatch(1);
        AtomicInteger firstCalls = new AtomicInteger();
        DispatchCapacity firstCapacity = new DispatchCapacity(CAPACITY);
        DispatchProcessor firstProcessor = dispatch -> {
            if (uuid(dispatch.getRunId()).equals(uuid(unfinished.getRunId()))) {
                unfinishedEntered.countDown();
                releaseUnfinished.await();
            } else {
                firstCalls.incrementAndGet();
            }
        };
        DispatchConsumerRunner firstRunner = runner(groupPrefix, firstProcessor, firstCapacity);
        String groupId = groupPrefix + "-high";
        TopicPartition partition = new TopicPartition(
                KairosTopic.RUN_HIGH.topicName(), metadata.get(0).partition());

        firstRunner.start();
        try {
            assertThat(unfinishedEntered.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();
            assertThat(firstCalls).hasValue(1);
            awaitCommitted(groupId, partition, metadata.get(1).offset());
        } finally {
            firstRunner.stop();
            firstRunner.join(Duration.ofSeconds(30));
            releaseUnfinished.countDown();
        }

        List<UUID> replayed = new ArrayList<>();
        DispatchCapacity restartedCapacity = new DispatchCapacity(CAPACITY);
        DispatchConsumerRunner restarted =
                runner(groupPrefix, dispatch -> replayed.add(uuid(dispatch.getRunId())), restartedCapacity);
        restarted.start();
        try {
            await(() -> replayed.size() >= 1, WAIT);
            assertThat(replayed).containsExactly(uuid(unfinished.getRunId()));
            await(() -> restartedCapacity.inFlight() == 0, WAIT);
        } finally {
            restarted.stop();
            restarted.join(Duration.ofSeconds(30));
        }
    }

    @Test
    void retryReleasesAdmissionBeforeBackingOffAndReacquiresForTheNextAttempt() throws Exception {
        String groupPrefix = groupPrefix();
        Dispatch dispatch = dispatch(
                TestIds.runId(11),
                TestIds.teamId().value(),
                TestIds.endpointId().value(),
                TestIds.jobId(11).value());
        UUID targetRun = uuid(dispatch.getRunId());
        publish(List.of(dispatch));
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        DispatchAdmission admission = new DispatchAdmission() {
            @Override
            public boolean acquireOrDefer(Dispatch candidate) {
                if (uuid(candidate.getRunId()).equals(targetRun)) acquisitions.incrementAndGet();
                return true;
            }

            @Override
            public void release(Dispatch candidate) {
                if (uuid(candidate.getRunId()).equals(targetRun) && releases.incrementAndGet() == 1)
                    firstRelease.countDown();
            }
        };
        DispatchProcessor processor = candidate -> {
            if (!uuid(candidate.getRunId()).equals(targetRun)) return;
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transient database failure");
            completed.countDown();
        };
        DispatchCapacity capacity = new DispatchCapacity(CAPACITY);
        DispatchConsumerRunner runner = runner(groupPrefix, processor, admission, capacity);

        runner.start();
        try {
            assertThat(firstRelease.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
            await(() -> releases.get() == 2, WAIT);
            assertThat(attempts).hasValue(2);
            assertThat(acquisitions).hasValue(2);
            assertThat(releases).hasValue(2);
        } finally {
            runner.stop();
            runner.join(Duration.ofSeconds(30));
        }
    }

    private DispatchConsumerRunner runner(String groupPrefix, DispatchProcessor processor, DispatchCapacity capacity) {
        return runner(
                groupPrefix,
                processor,
                new DispatchAdmission() {
                    @Override
                    public boolean acquireOrDefer(Dispatch dispatch) {
                        return true;
                    }

                    @Override
                    public void release(Dispatch dispatch) {}
                },
                capacity);
    }

    private DispatchConsumerRunner runner(
            String groupPrefix, DispatchProcessor processor, DispatchAdmission admission, DispatchCapacity capacity) {
        ExecutorConsumerProperties properties =
                new ExecutorConsumerProperties(CAPACITY, 3_000, Duration.ofSeconds(1), Duration.ofMillis(100));
        return new DispatchConsumerRunner(
                DispatchTier.HIGH,
                groupPrefix,
                factory,
                processor,
                admission,
                new PoisonMessages(meters),
                capacity,
                new PartitionFlowControl(CAPACITY, 3_000),
                properties,
                new ExecutorConsumerMetrics(meters, capacity));
    }

    private List<Dispatch> flood(UUID teamId, UUID endpointId, UUID targetId) {
        List<Dispatch> messages = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            messages.add(dispatch(TestIds.runId(7), teamId, endpointId, targetId));
        }
        return messages;
    }

    private static Dispatch dispatch(RunId run, UUID teamId, UUID endpointId, UUID targetId) {
        return Dispatch.newBuilder()
                .setRunId(bytes(run.value()))
                .setShard(run.shard().value())
                .setFence(1)
                .setAttempt(1)
                .setTeamId(bytes(teamId))
                .setTargetType(TargetType.TARGET_TYPE_JOB)
                .setDefinitionId(bytes(targetId))
                .setDefinitionVersion(1)
                .setTargetId(bytes(targetId))
                .setEndpointId(bytes(endpointId))
                .setEndpointVersion(1)
                .setPriority(9)
                .setTimeoutMs(5_000)
                .build();
    }

    private List<org.apache.kafka.clients.producer.RecordMetadata> publish(List<Dispatch> dispatches) throws Exception {
        List<OutgoingRecord> records = dispatches.stream()
                .map(dispatch -> OutgoingRecord.of(KairosTopic.RUN_HIGH, uuid(dispatch.getTargetId()), dispatch, null))
                .toList();
        return publisher.publish(records).stream()
                .map(future -> {
                    try {
                        return future.get(60, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new IllegalStateException("Kafka did not acknowledge a dispatch", failure);
                    }
                })
                .toList();
    }

    private void awaitCommitted(String groupId, TopicPartition partition, long expectedOffset) throws Exception {
        try (KairosConsumer<Dispatch> observer = factory.consumer(groupId, MessageType.DISPATCH, 1)) {
            Consumer<byte[], Decoded<Dispatch>> consumer = observer.consumer();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                OffsetAndMetadata committed =
                        consumer.committed(Set.of(partition)).get(partition);
                if (committed != null && committed.offset() == expectedOffset) {
                    return;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("The contiguous completed prefix was not committed");
        }
    }

    private static boolean await(CountDownLatch latch, Duration timeout) throws InterruptedException {
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean awaitCount(CountDownLatch latch, long count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (latch.getCount() <= count) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Condition did not become true before timeout");
    }

    private static String groupPrefix() {
        return "t241-" + UUID.randomUUID();
    }

    private static ByteString bytes(UUID id) {
        return ByteString.copyFrom(ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
    }

    private static UUID uuid(ByteString bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
