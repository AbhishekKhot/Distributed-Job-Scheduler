package dev.kairos.domain.retry;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.Objects;

/**
 * One entry of an endpoint's {@code successStatus} list: a status class such as {@code 2xx} or
 * an exact status such as {@code 204}.
 *
 * <p>Parsed by hand (exactly three characters, no regular expression): a digit 1 to 5 followed by
 * {@code xx} in lower case, or a status from 100 to 599.
 *
 * @param from the lowest matching status, inclusive
 * @param to the highest matching status, inclusive
 */
public record StatusPattern(int from, int to) {

    private static final String FIELD = "successStatus";
    private static final int LENGTH = 3;

    /**
     * Validates the range.
     *
     * @throws IllegalArgumentException unless {@code 100 <= from <= to <= 599}
     */
    public StatusPattern {
        if (from < HttpStatuses.MIN || to > HttpStatuses.MAX || from > to) {
            throw new IllegalArgumentException("A status pattern covers statuses 100 to 599");
        }
    }

    /**
     * Parses one entry.
     *
     * @param text the entry, for example {@code 2xx} or {@code 204}
     * @return the pattern
     * @throws ValidationFailedException on field {@code successStatus} with code
     *     {@link FieldError#FORMAT} if the entry is malformed
     */
    public static StatusPattern parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() != LENGTH) {
            throw ValidationFailedException.of(FIELD, FieldError.FORMAT);
        }
        char first = text.charAt(0);
        if (first < '1' || first > '5') {
            throw ValidationFailedException.of(FIELD, FieldError.FORMAT);
        }
        int hundreds = (first - '0') * 100;
        if (text.charAt(1) == 'x' && text.charAt(2) == 'x') {
            return new StatusPattern(hundreds, hundreds + 99);
        }
        if (!isDigit(text.charAt(1)) || !isDigit(text.charAt(2))) {
            throw ValidationFailedException.of(FIELD, FieldError.FORMAT);
        }
        int status = hundreds + (text.charAt(1) - '0') * 10 + (text.charAt(2) - '0');
        return new StatusPattern(status, status);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Tells whether a status matches.
     *
     * @param status an HTTP status
     * @return true if it lies in this pattern's range
     */
    public boolean matches(int status) {
        return status >= from && status <= to;
    }

    /** Returns the text form, for example {@code 2xx} or {@code 204}. */
    @Override
    public String toString() {
        return from == to ? Integer.toString(from) : (from / 100) + "xx";
    }
}
