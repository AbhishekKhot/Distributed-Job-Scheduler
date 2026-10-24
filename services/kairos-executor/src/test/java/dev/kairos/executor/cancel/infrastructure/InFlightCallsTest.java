package dev.kairos.executor.cancel.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.run.Fence;
import dev.kairos.engine.lock.LockToken;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.testsupport.TestIds;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InFlightCallsTest {
    @Test
    void onlyAValidCancelFenceAbortsTheActiveClaim() {
        InFlightCalls calls = new InFlightCalls();
        var runId = TestIds.runId(0);
        Dispatch dispatch = Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(runId.shard().value())
                .build();
        var lock = new RunLock(runId, new LockToken(new Fence(8), "test-node", "0123456789abcdef0123456789abcdef"));
        var cancellation = calls.register(dispatch, new Fence(8), "test-node", lock);

        assertThat(calls.cancel(runId, new Fence(7))).isFalse();
        assertThat(cancellation.isCancelled()).isFalse();
        assertThat(calls.cancel(runId, new Fence(8))).isTrue();
        assertThat(cancellation.isCancelled()).isTrue();

        calls.remove(runId, cancellation);
        assertThat(calls.cancel(runId, new Fence(9))).isFalse();
    }

    @Test
    void drainCancelsAndRequeuesUnsentCallsBeforeTheyCanSend() {
        InFlightCalls calls = new InFlightCalls();
        var runId = TestIds.runId(1);
        Dispatch dispatch = Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(runId.shard().value())
                .build();
        var lock = new RunLock(runId, new LockToken(new Fence(12), "test-node", "0123456789abcdef0123456789abcdef"));
        var cancellation = calls.register(dispatch, new Fence(12), "test-node", lock);

        Set<java.util.UUID> requeued = calls.requeueUnsent(Set.of(runId.value()), (queued, fence, node, activeLock) -> {
            assertThat(queued).isEqualTo(dispatch);
            assertThat(fence).isEqualTo(12);
            assertThat(node).isEqualTo("test-node");
            assertThat(activeLock).isEqualTo(lock);
            return true;
        });

        assertThat(requeued).containsExactly(runId.value());
        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(calls.markSent(runId, cancellation)).isFalse();
    }
}
