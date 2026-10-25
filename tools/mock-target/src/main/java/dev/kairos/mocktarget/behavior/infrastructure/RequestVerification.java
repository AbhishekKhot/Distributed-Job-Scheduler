package dev.kairos.mocktarget.behavior.infrastructure;

import dev.kairos.security.signing.RequestSignatures;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Reuses the target-side Kairos canonical HMAC verifier, including timestamp freshness. */
public final class RequestVerification {
    private final RequestSignatures verifier;
    private final boolean enabled;

    public RequestVerification(List<String> keys, Clock clock) {
        if (keys.size() > 2 || keys.stream().anyMatch(key -> key.length() > 128))
            throw new IllegalArgumentException("Invalid mock verification key configuration.");
        enabled = !keys.isEmpty();
        verifier = RequestSignatures.of(keys, clock, Duration.ofMinutes(5));
    }

    public boolean verify(String method, String path, String key, byte[] body, String timestamp, String signature) {
        return !enabled || (!key.isEmpty() && verifier.verify(method, path, key, body, timestamp, signature));
    }
}
