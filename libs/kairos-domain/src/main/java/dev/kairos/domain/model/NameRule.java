package dev.kairos.domain.model;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Validation rule for a name: a required value, a length range checked first, then an anchored
 * pattern without nested quantifiers. Checking the length before matching bounds the work done
 * on untrusted input. Immutable and thread-safe.
 */
final class NameRule {

    private final String field;
    private final int minLength;
    private final int maxLength;
    private final Pattern pattern;

    NameRule(String field, int minLength, int maxLength, String regex) {
        this.field = field;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.pattern = Pattern.compile(regex);
    }

    /**
     * Returns the value if it satisfies the rule.
     *
     * @throws ValidationFailedException with code {@code required}, {@code length} or
     *     {@code pattern} on this rule's field
     */
    String check(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            throw ValidationFailedException.of(field, FieldError.REQUIRED);
        }
        if (value.length() < minLength || value.length() > maxLength) {
            throw ValidationFailedException.of(field, FieldError.LENGTH);
        }
        if (!pattern.matcher(value).matches()) {
            throw ValidationFailedException.of(field, FieldError.PATTERN);
        }
        return value;
    }
}
