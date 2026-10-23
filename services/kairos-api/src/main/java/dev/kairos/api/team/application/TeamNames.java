package dev.kairos.api.team.application;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.List;

/**
 * Team names (LLD section 2.2: {@code teams.slug}, {@code ^[a-z][a-z0-9-]{2,62}$}). The check is a character loop, so
 * no regular expression ever runs on client input; the length is checked first. Names are not lower-cased: an
 * upper-case letter is refused rather than silently changed. Stateless and thread-safe.
 */
public final class TeamNames {

    /** The LLD rule of team names. */
    public static final String PATTERN = "^[a-z][a-z0-9-]{2,62}$";

    /** LLD message of 400 {@code validation} on the name. */
    public static final String INVALID_DETAIL = "Team name must match " + PATTERN + ".";

    /** The shortest name. */
    public static final int MIN_LENGTH = 3;

    /** The longest name. */
    public static final int MAX_LENGTH = 63;

    private TeamNames() {}

    /**
     * Checks a submitted team name.
     *
     * @param name the submitted name
     * @return the name, which is the team's slug
     * @throws ValidationFailedException 400 {@code validation} with field {@code name} and code {@code pattern}
     */
    public static String validate(String name) {
        if (!isValid(name)) {
            throw new ValidationFailedException(
                    ErrorCode.VALIDATION, INVALID_DETAIL, List.of(new FieldError("name", FieldError.PATTERN)));
        }
        return name;
    }

    /**
     * Tells whether a name follows the rule.
     *
     * @param name the name
     * @return whether it matches {@value #PATTERN}
     */
    public static boolean isValid(String name) {
        if (name.length() < MIN_LENGTH || name.length() > MAX_LENGTH) {
            return false;
        }
        char first = name.charAt(0);
        if (first < 'a' || first > 'z') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
