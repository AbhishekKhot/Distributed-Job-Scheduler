package dev.kairos.persistence.error;

import java.util.Objects;

/**
 * A transaction failed for a reason that a fresh attempt of the whole transaction can fix: a serialization failure
 * or a deadlock. The database has rolled the transaction back.
 *
 * <p>Callers retry the whole unit of work, never a single statement, usually through {@link TransientRetry}. The
 * message is fixed and never contains SQL or values; the driver's exception is kept as the cause for the logs.
 */
public final class RetryableDatabaseException extends RuntimeException {

    /** Why the transaction was rolled back. */
    public enum Reason {
        /** SQLSTATE {@code 40001}: concurrent transactions could not be serialised. */
        SERIALIZATION_FAILURE,
        /** SQLSTATE {@code 40P01}: the transaction was chosen as the victim of a deadlock. */
        DEADLOCK
    }

    /** Why the transaction was rolled back. */
    private final Reason reason;

    /**
     * Creates an exception.
     *
     * @param reason why the transaction was rolled back
     * @param cause the driver's exception
     */
    public RetryableDatabaseException(Reason reason, Throwable cause) {
        super("Transaction rolled back by the database (" + reason + "); retry the whole transaction", cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns why the transaction was rolled back.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
