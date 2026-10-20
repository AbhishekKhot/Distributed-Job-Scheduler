package dev.kairos.domain.model;

import org.jspecify.annotations.Nullable;

/**
 * Unique name of a team ({@code teams.slug}), for example {@code payments}.
 *
 * <p>3 to 63 characters matching {@code ^[a-z][a-z0-9-]{2,62}$}.
 *
 * @param value the validated slug
 */
public record TeamSlug(String value) {

    /** The pattern from LLD section 2.2. */
    public static final String PATTERN = "^[a-z][a-z0-9-]{2,62}$";

    private static final NameRule RULE = new NameRule("name", 3, 63, PATTERN);

    /**
     * Validates the slug.
     *
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code name} if the value
     *     is missing, too long or short, or does not match {@link #PATTERN}
     */
    public TeamSlug {
        RULE.check(value);
    }

    /**
     * Validates and wraps a slug.
     *
     * @param value the submitted value, possibly null
     * @return the slug
     * @throws dev.kairos.domain.error.ValidationFailedException if the value is invalid
     */
    public static TeamSlug of(@Nullable String value) {
        return new TeamSlug(RULE.check(value));
    }

    @Override
    public String toString() {
        return value;
    }
}
