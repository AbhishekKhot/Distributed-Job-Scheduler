package dev.kairos.api.support.idempotency;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The response of the first request with a key, replayed byte for byte to repeats.
 *
 * @param status the HTTP status
 * @param contentType the content type, or {@code null} for an empty body
 * @param headers the replayed headers ({@code Location}, {@code ETag}), unmodifiable
 * @param body the body text, or {@code null} if empty
 */
public record StoredResponse(
        int status,
        @Nullable String contentType,
        Map<String, String> headers,
        @Nullable String body) {

    /** The headers that are stored and replayed. */
    public static final java.util.List<String> REPLAYED_HEADERS = java.util.List.of("Location", "ETag");

    /** Validates the components and copies the headers. */
    public StoredResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP status");
        }
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    }
}
