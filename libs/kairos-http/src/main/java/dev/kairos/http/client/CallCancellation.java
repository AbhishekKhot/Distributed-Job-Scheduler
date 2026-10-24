package dev.kairos.http.client;

import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/** Thread-safe cancellation signal for one outbound call. */
public final class CallCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final AtomicBoolean drainAbort = new AtomicBoolean();
    private volatile @Nullable Runnable abort;
    private boolean finished;

    /** Creates an inactive cancellation signal. */
    public CallCancellation() {}

    /** Requests cancellation and aborts the active HTTP exchange, if it has started. */
    public synchronized void cancel() {
        if (finished) return;
        cancelled.set(true);
        abort();
    }

    /** Aborts an exchange after the graceful drain deadline without treating it as a user cancellation. */
    public synchronized void abortForDrain() {
        if (finished) return;
        drainAbort.set(true);
        abort();
    }

    /**
     * Tells whether cancellation has been requested.
     *
     * @return true after {@link #cancel()} was called
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    boolean isDrainAbort() {
        return drainAbort.get();
    }

    private synchronized void abort() {
        if (aborted.compareAndSet(false, true)) {
            Runnable active = abort;
            if (active != null) active.run();
        }
    }

    synchronized void attach(Runnable abortAction) {
        abort = abortAction;
        if (aborted.get()) abortAction.run();
    }

    synchronized void detach() {
        finished = true;
        abort = null;
    }
}
