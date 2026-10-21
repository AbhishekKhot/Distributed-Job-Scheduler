package dev.kairos.api.support.paging;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import org.jspecify.annotations.Nullable;

/** The {@code limit} query parameter of list operations: 1 to 200, 50 when absent (LLD section 3.1). */
public final class PageLimit {

    /** The limit when the parameter is absent. */
    public static final int DEFAULT = 50;

    /** The smallest limit. */
    public static final int MIN = 1;

    /** The largest limit. */
    public static final int MAX = 200;

    private PageLimit() {}

    /**
     * Resolves the requested limit.
     *
     * @param limit the {@code limit} parameter, or {@code null} if absent
     * @return the limit to use
     * @throws ValidationFailedException with field {@code limit} and code {@code range} if it is outside 1 to 200
     */
    public static int resolve(@Nullable Integer limit) {
        if (limit == null) {
            return DEFAULT;
        }
        if (limit < MIN || limit > MAX) {
            throw ValidationFailedException.of("limit", FieldError.RANGE);
        }
        return limit;
    }
}
