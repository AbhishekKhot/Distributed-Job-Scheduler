package dev.kairos.executor.cancel.infrastructure;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.http.client.CallCancellation;
import dev.kairos.proto.v1.Dispatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tracks locally owned HTTP calls so a broadcast cancel can abort the matching fenced attempt. */
public final class InFlightCalls {
    private final ConcurrentMap<UUID, ActiveCall> calls = new ConcurrentHashMap<>();

    /** Adds a claimed run; returns its cancellation signal. */
    public CallCancellation register(Dispatch dispatch, Fence fence, String ownerNode, RunLock lock) {
        UUID runId = dev.kairos.executor.consume.application.DispatchIds.uuid(dispatch.getRunId());
        ActiveCall active = new ActiveCall(dispatch, fence.value(), ownerNode, lock, new CallCancellation());
        if (calls.putIfAbsent(runId, active) != null) {
            throw new IllegalStateException("A run already has an active local call");
        }
        return active.cancellation();
    }

    /** Marks the send cut-off so drain requeue cannot race an HTTP request. */
    public boolean markSent(RunId runId, CallCancellation cancellation) {
        ActiveCall active = calls.get(runId.value());
        return active != null && active.markSent(cancellation);
    }

    /** Aborts the active call when the cancel fence covers its claim. */
    public boolean cancel(RunId runId, Fence cancelFence) {
        ActiveCall active = calls.get(runId.value());
        if (active == null || cancelFence.value() < active.fence()) return false;
        active.cancellation().cancel();
        return true;
    }

    /** Removes a completed call without affecting a newer call for the same run. */
    public void remove(RunId runId, CallCancellation cancellation) {
        calls.computeIfPresent(runId.value(), (_, active) -> active.cancellation() == cancellation ? null : active);
    }

    /** Cancels and requeues the selected active calls that have not crossed the send cut-off. */
    public Set<UUID> requeueUnsent(Set<UUID> runIds, DrainRequeuer requeuer) {
        List<ActiveCall> snapshot = new ArrayList<>(calls.values());
        Set<UUID> requeued = ConcurrentHashMap.newKeySet();
        for (ActiveCall active : snapshot) {
            UUID runId = dev.kairos.executor.consume.application.DispatchIds.uuid(
                    active.dispatch().getRunId());
            if (!runIds.contains(runId)) continue;
            if (active.beginDrain()) {
                try {
                    if (requeuer.requeue(active.dispatch(), active.fence(), active.ownerNode(), active.lock())) {
                        requeued.add(runId);
                    }
                } catch (RuntimeException failure) {
                    active.allowRecovery();
                    System.getLogger(InFlightCalls.class.getName())
                            .log(System.Logger.Level.ERROR, "Unsent run requeue failed; lease recovery will retry it");
                }
            }
        }
        return Set.copyOf(requeued);
    }

    /** Stops sent exchanges that remained active at the end of the drain; their results remain unknown outcomes. */
    public void abortSentAfterDrain() {
        calls.values().stream()
                .filter(ActiveCall::isSent)
                .forEach(active -> active.cancellation().abortForDrain());
    }

    @FunctionalInterface
    public interface DrainRequeuer {
        boolean requeue(Dispatch dispatch, int claimedFence, String ownerNode, RunLock lock);
    }

    private static final class ActiveCall {
        private final Dispatch dispatch;
        private final int fence;
        private final String ownerNode;
        private final RunLock lock;
        private final CallCancellation cancellation;
        private boolean sent;
        private boolean draining;

        private ActiveCall(
                Dispatch dispatch, int fence, String ownerNode, RunLock lock, CallCancellation cancellation) {
            this.dispatch = dispatch;
            this.fence = fence;
            this.ownerNode = ownerNode;
            this.lock = lock;
            this.cancellation = cancellation;
        }

        private synchronized boolean markSent(CallCancellation signal) {
            if (cancellation != signal || draining) return false;
            sent = true;
            return true;
        }

        private synchronized boolean isSent() {
            return sent;
        }

        private synchronized boolean beginDrain() {
            if (sent || draining) return false;
            draining = true;
            cancellation.cancel();
            return true;
        }

        private synchronized void allowRecovery() {
            draining = false;
        }

        private Dispatch dispatch() {
            return dispatch;
        }

        private int fence() {
            return fence;
        }

        private String ownerNode() {
            return ownerNode;
        }

        private RunLock lock() {
            return lock;
        }

        private CallCancellation cancellation() {
            return cancellation;
        }
    }
}
