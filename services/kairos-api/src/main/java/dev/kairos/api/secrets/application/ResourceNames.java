package dev.kairos.api.secrets.application;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import org.jspecify.annotations.Nullable;

/** Path-safe names shared by credential resources, with work bounded before matching. */
public final class ResourceNames {
    private ResourceNames() {}

    public static String check(@Nullable String name) {
        if (name == null || name.isEmpty()) throw ValidationFailedException.of("name", FieldError.REQUIRED);
        if (name.length() > 128) throw ValidationFailedException.of("name", FieldError.LENGTH);
        if (!name.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) {
            throw ValidationFailedException.of("name", FieldError.PATTERN);
        }
        return name;
    }
}
