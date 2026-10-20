package dev.kairos.domain.retry;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How an endpoint wants HTTP statuses judged: {@code successStatus} and {@code statusOverrides}
 * from its spec. An override wins over the success list. Immutable.
 *
 * @param successStatus the statuses that mean success, 1 to 10 entries
 * @param statusOverrides forced treatments per status (100 to 599), at most 50
 */
public record StatusRules(List<StatusPattern> successStatus, Map<Integer, StatusOverride> statusOverrides) {

    /** Most entries in {@code successStatus}. */
    public static final int MAX_SUCCESS_PATTERNS = 10;

    /** Most entries in {@code statusOverrides}. */
    public static final int MAX_OVERRIDES = 50;

    /** The default: {@code 2xx} is success and nothing is overridden. */
    public static final StatusRules DEFAULT = new StatusRules(List.of(new StatusPattern(200, 299)), Map.of());

    private static final String SUCCESS_FIELD = "successStatus";
    private static final String OVERRIDES_FIELD = "statusOverrides";
    private static final int STATUS_LENGTH = 3;

    /**
     * Validates and copies the components.
     *
     * @throws ValidationFailedException on field {@code successStatus} ({@link FieldError#REQUIRED}
     *     if empty, {@link FieldError#LENGTH} if too long) or {@code statusOverrides}
     *     ({@link FieldError#LENGTH} if too long, {@link FieldError#RANGE} for a status outside 100
     *     to 599)
     * @throws NullPointerException if a component, entry, key or value is null
     */
    public StatusRules {
        successStatus = List.copyOf(successStatus);
        statusOverrides = Map.copyOf(statusOverrides);
        if (successStatus.isEmpty()) {
            throw ValidationFailedException.of(SUCCESS_FIELD, FieldError.REQUIRED);
        }
        if (successStatus.size() > MAX_SUCCESS_PATTERNS) {
            throw ValidationFailedException.of(SUCCESS_FIELD, FieldError.LENGTH);
        }
        if (statusOverrides.size() > MAX_OVERRIDES) {
            throw ValidationFailedException.of(OVERRIDES_FIELD, FieldError.LENGTH);
        }
        for (int status : statusOverrides.keySet()) {
            if (status < HttpStatuses.MIN || status > HttpStatuses.MAX) {
                throw ValidationFailedException.of(OVERRIDES_FIELD, FieldError.RANGE);
            }
        }
    }

    /**
     * Builds the rules from their JSON form, where override keys are strings.
     *
     * @param successStatus entries such as {@code 2xx} or {@code 204}
     * @param statusOverrides overrides keyed by a three-digit status
     * @return the rules
     * @throws ValidationFailedException if an entry or key is malformed or a limit is exceeded
     */
    public static StatusRules parse(List<String> successStatus, Map<String, StatusOverride> statusOverrides) {
        if (successStatus.size() > MAX_SUCCESS_PATTERNS) {
            throw ValidationFailedException.of(SUCCESS_FIELD, FieldError.LENGTH);
        }
        if (statusOverrides.size() > MAX_OVERRIDES) {
            throw ValidationFailedException.of(OVERRIDES_FIELD, FieldError.LENGTH);
        }
        List<StatusPattern> patterns =
                successStatus.stream().map(StatusPattern::parse).toList();
        Map<Integer, StatusOverride> overrides = new HashMap<>();
        statusOverrides.forEach((key, value) -> overrides.put(parseStatus(key), Objects.requireNonNull(value)));
        return new StatusRules(patterns, overrides);
    }

    private static int parseStatus(String text) {
        if (text.length() != STATUS_LENGTH) {
            throw ValidationFailedException.of(OVERRIDES_FIELD, FieldError.FORMAT);
        }
        int status = 0;
        for (int i = 0; i < STATUS_LENGTH; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw ValidationFailedException.of(OVERRIDES_FIELD, FieldError.FORMAT);
            }
            status = status * 10 + (c - '0');
        }
        return status;
    }

    /**
     * Tells whether a status is listed in {@code successStatus}, ignoring overrides.
     *
     * @param status an HTTP status
     * @return true if any pattern matches
     */
    public boolean isListedAsSuccess(int status) {
        for (StatusPattern pattern : successStatus) {
            if (pattern.matches(status)) {
                return true;
            }
        }
        return false;
    }
}
