package dev.kairos.executor.cancel.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.lock.LockToken;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.KafkaProperties;
import dev.kairos.messaging.client.KafkaPublisher;
import dev.kairos.messaging.client.OutgoingRecord;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.CancelReason;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.testsupport.Awaits;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosKafka;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Each executor pod receives a copy of a cancel broadcast. */
@KairosIntegrationTest(postgres = false, redis = false)
class CancelConsumerIntegrationTest {
    @Test
    void everyPodGroupAbortsItsMatchingFencedCall(KairosKafka kafka) throws Exception {
        var runId = TestIds.runId(19);
        Dispatch dispatch = Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(runId.shard().value())
                .build();
        InFlightCalls firstCalls = new InFlightCalls();
        InFlightCalls secondCalls = new InFlightCalls();
        var first = firstCalls.register(
                dispatch,
                new Fence(7),
                "node-a",
                new RunLock(runId, new LockToken(new Fence(7), "node-a", "0123456789abcdef0123456789abcdef")));
        var second = secondCalls.register(
                dispatch,
                new Fence(7),
                "node-b",
                new RunLock(runId, new LockToken(new Fence(7), "node-b", "abcdef0123456789abcdef0123456789")));
        KafkaClientFactory factory =
                new KafkaClientFactory(KafkaProperties.plaintext(kafka.bootstrapServers()), new SimpleMeterRegistry());
        KafkaPublisher publisher = factory.publisher("t244-cancel-it");
        CancelConsumer firstConsumer = new CancelConsumer(factory, firstCalls, "pod-a");
        CancelConsumer secondConsumer = new CancelConsumer(factory, secondCalls, "pod-b");
        Instant requestedAt = Instant.parse("2026-10-24T12:00:00Z");
        Cancel cancel = Cancel.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(runId.shard().value())
                .setFence(7)
                .setReason(CancelReason.CANCEL_REASON_REQUESTED)
                .setRequestedAt(Timestamp.newBuilder()
                        .setSeconds(requestedAt.getEpochSecond())
                        .setNanos(requestedAt.getNano()))
                .build();
        try {
            firstConsumer.start();
            secondConsumer.start();
            Awaits.await(Duration.ofSeconds(10)).until(() -> firstConsumer.isReady() && secondConsumer.isReady());
            publisher
                    .publish(OutgoingRecord.of(KairosTopic.RUN_CANCEL, runId.value(), cancel, null))
                    .get(10, TimeUnit.SECONDS);
            Awaits.await(Duration.ofSeconds(10)).until(() -> first.isCancelled() && second.isCancelled());
            assertThat(first.isCancelled()).isTrue();
            assertThat(second.isCancelled()).isTrue();
        } finally {
            firstConsumer.stop();
            secondConsumer.stop();
            publisher.close();
        }
    }
}
