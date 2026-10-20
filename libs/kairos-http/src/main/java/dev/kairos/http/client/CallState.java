package dev.kairos.http.client;

import java.io.IOException;
import java.io.Serial;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Per-call state shared between {@link OutboundHttpClient} and {@link ObservingRequestExecutor} through the HTTP
 * context: runs the {@link SendObserver}, records whether the request was sent, and enforces the overall deadline.
 *
 * <p>The deadline starts when the request is sent and lasts the endpoint timeout, matching the send-time guard's
 * budget (LLD section 4.1: the lock must outlive timeout + 2s). The response timeout alone is a read-inactivity
 * timeout, so a target trickling bytes could otherwise hold the call far longer. On expiry the request is cancelled,
 * which closes its connection only while this call holds it.
 */
final class CallState {

    /** The HTTP context attribute holding the state. */
    static final String ATTRIBUTE = "dev.kairos.http.call-state";

    private final SendObserver observer;
    private final SendContext sendContext;
    private final Duration deadline;
    private final ScheduledExecutorService scheduler;
    private final Runnable abort;

    // Written by the calling thread; the deadline flag by the scheduler thread.
    private volatile boolean sent;
    private volatile boolean vetoed;
    private volatile boolean deadlineExceeded;
    private volatile boolean finished;
    private volatile @Nullable String vetoReason;
    private volatile @Nullable ScheduledFuture<?> deadlineTask;

    CallState(
            SendObserver observer,
            SendContext sendContext,
            Duration deadline,
            ScheduledExecutorService scheduler,
            Runnable abort) {
        this.observer = observer;
        this.sendContext = sendContext;
        this.deadline = deadline;
        this.scheduler = scheduler;
        this.abort = abort;
    }

    /**
     * Runs the observer and, if it lets the request through, marks the request sent and starts the deadline.
     *
     * @throws VetoedSend if the observer refused, or if this call already sent once
     */
    void beforeSend() throws VetoedSend {
        if (sent || vetoed) {
            // At most one send per call: redirects and retries are off, so a second send is a bug; refuse it.
            vetoed = true;
            vetoReason = "a call sends at most once";
            throw new VetoedSend(null);
        }
        try {
            observer.beforeSend(sendContext);
        } catch (SendVetoedException | RuntimeException e) {
            // Any failure of the guard counts as a veto: the execution gates fail closed.
            vetoed = true;
            vetoReason = e instanceof SendVetoedException ? e.getMessage() : "the send observer failed";
            throw new VetoedSend(e);
        }
        sent = true;
        deadlineTask = scheduler.schedule(this::expire, deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops the deadline; called once the call is over. */
    void finish() {
        finished = true;
        ScheduledFuture<?> task = deadlineTask;
        if (task != null) {
            task.cancel(false);
        }
    }

    boolean sent() {
        return sent;
    }

    boolean vetoed() {
        return vetoed;
    }

    boolean deadlineExceeded() {
        return deadlineExceeded;
    }

    String vetoReason() {
        String reason = vetoReason;
        return reason == null ? "the send was stopped" : reason;
    }

    private void expire() {
        if (!finished) {
            deadlineExceeded = true;
            abort.run();
        }
    }

    /** Signals a veto through HttpClient, which only lets I/O exceptions through its execution chain. */
    static final class VetoedSend extends IOException {

        @Serial
        private static final long serialVersionUID = 1L;

        VetoedSend(@Nullable Throwable cause) {
            super("the send was stopped before any byte was written", cause);
        }
    }
}
