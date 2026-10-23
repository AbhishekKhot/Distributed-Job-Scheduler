package dev.kairos.api.secrets.application;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Explicit credential fields. Unknown JSON fields are refused; diagnostics never expose values. */
public record CredentialValue(
        @Nullable String username,
        @Nullable String password,
        @Nullable String token,
        @Nullable String key,
        @Nullable String clientId,
        @Nullable String clientSecret) {
    public static final String SHAPE_DETAIL = "`value` must match the kind: BASIC {username, password}, BEARER {token},"
            + " API_KEY {key}, OAUTH2_CLIENT {clientId, clientSecret}.";

    public enum Kind {
        BASIC,
        BEARER,
        API_KEY,
        OAUTH2_CLIENT
    }

    public void validate(Kind kind, boolean creating) {
        Map<String, String> provided = fields();
        List<String> expected = switch (kind) {
            case BASIC -> List.of("username", "password");
            case BEARER -> List.of("token");
            case API_KEY -> List.of("key");
            case OAUTH2_CLIENT -> List.of("clientId", "clientSecret");
        };
        List<FieldError> errors = new ArrayList<>();
        for (String field : expected) {
            String value = provided.get(field);
            if (value == null || value.isEmpty()) errors.add(new FieldError("value." + field, FieldError.REQUIRED));
        }
        provided.forEach((field, value) -> {
            if (!expected.contains(field)) errors.add(new FieldError("value." + field, "unexpected-field"));
            if (value.length() > 8192) errors.add(new FieldError("value." + field, FieldError.LENGTH));
            else if (value.chars().anyMatch(c -> c < 32 || c == 127)
                    || (field.equals("token") && value.chars().anyMatch(c -> c < 33 || c > 126))
                    || (field.equals("username") && value.indexOf(':') >= 0)) {
                errors.add(new FieldError("value." + field, FieldError.FORMAT));
            }
        });
        if (!errors.isEmpty()) throw invalid(errors, creating);
    }

    public Map<String, String> fields() {
        Map<String, String> result = new LinkedHashMap<>();
        if (username != null) result.put("username", username);
        if (password != null) result.put("password", password);
        if (token != null) result.put("token", token);
        if (key != null) result.put("key", key);
        if (clientId != null) result.put("clientId", clientId);
        if (clientSecret != null) result.put("clientSecret", clientSecret);
        return Map.copyOf(result);
    }

    public static ValidationFailedException invalid(List<FieldError> errors, boolean creating) {
        return new ValidationFailedException(
                ErrorCode.VALIDATION, creating ? SHAPE_DETAIL : ValidationFailedException.DEFAULT_DETAIL, errors);
    }

    @Override
    public String toString() {
        return "CredentialValue[<redacted>]";
    }
}
