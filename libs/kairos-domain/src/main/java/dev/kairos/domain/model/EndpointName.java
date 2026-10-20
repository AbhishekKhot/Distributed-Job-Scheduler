package dev.kairos.domain.model;

import org.jspecify.annotations.Nullable;

/**
 * Name of a registered endpoint, unique within a team ({@code api_endpoints.name}), for example
 * {@code orders-create}.
 *
 * <p>2 to 63 characters matching {@code ^[a-z][a-z0-9-]{1,62}$}.
 *
 * @param value the validated name
 */
public record EndpointName(String value) {

    /** The pattern from LLD section 2.2. */
    public static final String PATTERN = "^[a-z][a-z0-9-]{1,62}$";

    private static final NameRule RULE = new NameRule("name", 2, 63, PATTERN);

    /**
     * Validates the name.
     *
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code name} if the value
     *     is missing, too long or short, or does not match {@link #PATTERN}
     */
    public EndpointName {
        RULE.check(value);
    }

    /**
     * Validates and wraps a name.
     *
     * @param value the submitted value, possibly null
     * @return the name
     * @throws dev.kairos.domain.error.ValidationFailedException if the value is invalid
     */
    public static EndpointName of(@Nullable String value) {
        return new EndpointName(RULE.check(value));
    }

    @Override
    public String toString() {
        return value;
    }
}
