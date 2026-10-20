package dev.kairos.persistence.error;

import dev.kairos.domain.error.KairosException;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Turns database failures into the exceptions the rest of Kairos handles.
 *
 * <table>
 *   <caption>Translations</caption>
 *   <tr><th>SQLSTATE</th><th>Becomes</th></tr>
 *   <tr><td>{@code 23505} unique violation</td>
 *       <td>{@link dev.kairos.domain.error.ConflictException}, with the code registered for the violated constraint
 *           in {@link ConstraintConflicts}</td></tr>
 *   <tr><td>{@code 40001} serialization failure</td><td>{@link RetryableDatabaseException}</td></tr>
 *   <tr><td>{@code 40P01} deadlock</td><td>{@link RetryableDatabaseException}</td></tr>
 *   <tr><td>anything else</td><td>the original exception, unchanged</td></tr>
 * </table>
 *
 * <p>The SQLSTATE is read from the first {@link SQLException} in the cause chain, so the translation works on raw
 * driver exceptions as well as on Spring's {@code DataAccessException} wrappers. The original exception stays
 * attached as the cause; nothing from it reaches a client, because the conflict detail is a fixed sentence.
 *
 * <p>Stateless and thread-safe.
 */
public final class SqlErrorTranslator {

    /** SQLSTATE of a unique constraint violation. */
    public static final String UNIQUE_VIOLATION = "23505";

    /** SQLSTATE of a serialization failure. */
    public static final String SERIALIZATION_FAILURE = "40001";

    /** SQLSTATE of a detected deadlock. */
    public static final String DEADLOCK_DETECTED = "40P01";

    /** Bounds the walk down the cause chain, in case a chain ever loops. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private SqlErrorTranslator() {}

    /**
     * Translates a failure.
     *
     * @param failure the failure, typically a {@code DataAccessException}
     * @param conflicts the conflicts to report for unique violations
     * @return the translated exception, or {@code failure} itself if it needs no translation
     */
    public static RuntimeException translate(RuntimeException failure, ConstraintConflicts conflicts) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(conflicts, "conflicts");
        if (failure instanceof KairosException || failure instanceof RetryableDatabaseException) {
            // Already translated, for example by a nested call that joined the same transaction.
            return failure;
        }
        SQLException sqlException = findSqlException(failure);
        if (sqlException == null) {
            return failure;
        }
        String state = sqlException.getSQLState();
        if (UNIQUE_VIOLATION.equals(state)) {
            RuntimeException conflict = conflicts.toException(constraintOf(sqlException));
            conflict.initCause(failure);
            return conflict;
        }
        if (SERIALIZATION_FAILURE.equals(state)) {
            return new RetryableDatabaseException(RetryableDatabaseException.Reason.SERIALIZATION_FAILURE, failure);
        }
        if (DEADLOCK_DETECTED.equals(state)) {
            return new RetryableDatabaseException(RetryableDatabaseException.Reason.DEADLOCK, failure);
        }
        return failure;
    }

    /**
     * Returns the SQLSTATE of the first {@link SQLException} in a cause chain.
     *
     * @param failure the failure
     * @return the SQLSTATE, or {@code null} if the chain holds no SQL exception or it carries no state
     */
    public static @Nullable String sqlState(Throwable failure) {
        SQLException sqlException = findSqlException(failure);
        return sqlException == null ? null : sqlException.getSQLState();
    }

    private static @Nullable SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static @Nullable String constraintOf(SQLException sqlException) {
        if (sqlException instanceof PSQLException psqlException) {
            ServerErrorMessage message = psqlException.getServerErrorMessage();
            return message == null ? null : message.getConstraint();
        }
        return null;
    }
}
