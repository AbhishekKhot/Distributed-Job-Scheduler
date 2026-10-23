package dev.kairos.domain.template;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.Objects;

/**
 * A declared endpoint output, resolved into each workflow task before graph validation.
 * @param from response component
 * @param path bounded BODY selector or HEADER name; empty for STATUS
 * @param required whether missing data fails the call
 * @param sensitive whether the value must be sealed and kept out of URLs
 */
public record OutputSpec(Source from, String path, boolean required, boolean sensitive) {
    /** Validates the selector at definition time. */
    public OutputSpec {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(path, "path");
        switch (from) {
            case BODY -> OutputPath.parse(path);
            case HEADER -> {
                if (path.isEmpty() || path.length() > 128 || !path.matches("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$"))
                    throw invalid();
            }
            case STATUS -> {
                if (!path.isEmpty()) throw invalid();
            }
        }
    }

    /** Response sources supported without an expression language. */
    public enum Source {
        /** A root/dot-property/array-index selector. */
        BODY,
        /** One case-insensitive, single-valued header. */
        HEADER,
        /** Numeric HTTP status. */
        STATUS
    }

    static ValidationFailedException invalid() {
        return ValidationFailedException.of("outputs.path", FieldError.FORMAT);
    }
}
