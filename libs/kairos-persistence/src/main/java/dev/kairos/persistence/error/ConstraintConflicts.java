package dev.kairos.persistence.error;

import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Maps unique constraints to the conflict a client sees when an insert or update violates them.
 *
 * <p>A repository that knows which constraint guards a name or a key registers it here, for example
 * {@code jobs_team_name_uk} to {@link ErrorCode#JOB_NAME_TAKEN}. A violation of any other unique constraint becomes a
 * generic {@link ErrorCode#INVALID_STATE} conflict. Details are fixed sentences written by the caller; they never
 * contain the violating values, which PostgreSQL puts in its own error message.
 *
 * <p>Immutable and thread-safe.
 */
public final class ConstraintConflicts {

    /** Detail of the conflict reported for a constraint nobody registered. */
    public static final String DEFAULT_DETAIL = "The request conflicts with the current state of the resource.";

    private static final int CONFLICT_STATUS = 409;
    private static final ConstraintConflicts NONE = new ConstraintConflicts(Map.of());

    private final Map<String, Conflict> byConstraint;

    private ConstraintConflicts(Map<String, Conflict> byConstraint) {
        this.byConstraint = Map.copyOf(byConstraint);
    }

    /**
     * The conflict reported for one constraint.
     *
     * @param errorCode a code with status 409
     * @param detail a fixed, client-safe description
     */
    public record Conflict(ErrorCode errorCode, String detail) {

        /**
         * Validates the conflict.
         *
         * @throws IllegalArgumentException if the code's status is not 409 or the detail is blank
         */
        public Conflict {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(detail, "detail");
            if (errorCode.status() != CONFLICT_STATUS) {
                throw new IllegalArgumentException(errorCode + " is not a conflict");
            }
            if (detail.isBlank()) {
                throw new IllegalArgumentException("The detail must not be blank");
            }
        }
    }

    /**
     * Returns the mapping with no registered constraint: every violation becomes the generic conflict.
     *
     * @return the empty mapping
     */
    public static ConstraintConflicts none() {
        return NONE;
    }

    /**
     * Returns a mapping with one constraint.
     *
     * @param constraint the constraint name, as declared in the migration
     * @param errorCode a code with status 409
     * @param detail a fixed, client-safe description
     * @return the mapping
     * @throws IllegalArgumentException if the code's status is not 409, or the constraint or detail is blank
     */
    public static ConstraintConflicts of(String constraint, ErrorCode errorCode, String detail) {
        return NONE.and(constraint, errorCode, detail);
    }

    /**
     * Returns a copy of this mapping with one more constraint.
     *
     * @param constraint the constraint name, as declared in the migration
     * @param errorCode a code with status 409
     * @param detail a fixed, client-safe description
     * @return the extended mapping
     * @throws IllegalArgumentException if the code's status is not 409, the constraint or detail is blank, or the
     *     constraint is already mapped
     */
    public ConstraintConflicts and(String constraint, ErrorCode errorCode, String detail) {
        Objects.requireNonNull(constraint, "constraint");
        if (constraint.isBlank()) {
            throw new IllegalArgumentException("The constraint name must not be blank");
        }
        if (byConstraint.containsKey(constraint)) {
            throw new IllegalArgumentException("Constraint " + constraint + " is already mapped");
        }
        Map<String, Conflict> extended = new HashMap<>(byConstraint);
        extended.put(constraint, new Conflict(errorCode, detail));
        return new ConstraintConflicts(extended);
    }

    /**
     * Returns the conflict for a violated constraint.
     *
     * @param constraint the violated constraint, or {@code null} if the driver did not report it
     * @return the registered conflict, or the generic {@link ErrorCode#INVALID_STATE} conflict
     */
    public Conflict conflictFor(@Nullable String constraint) {
        Conflict conflict = constraint == null ? null : byConstraint.get(constraint);
        return conflict != null ? conflict : new Conflict(ErrorCode.INVALID_STATE, DEFAULT_DETAIL);
    }

    /**
     * Builds the exception for a violated constraint.
     *
     * @param constraint the violated constraint, or {@code null} if the driver did not report it
     * @return a new exception with the registered or the generic conflict
     */
    public ConflictException toException(@Nullable String constraint) {
        Conflict conflict = conflictFor(constraint);
        return new ConflictException(conflict.errorCode(), conflict.detail());
    }
}
