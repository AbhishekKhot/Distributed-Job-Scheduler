package dev.kairos.api.signing.infrastructure;

import dev.kairos.api.signing.application.SigningUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically removes expired previous keys. Optimistic replacements make multiple API replicas safe. */
public final class SigningKeyPruner {
    private static final Logger LOG = LoggerFactory.getLogger(SigningKeyPruner.class);
    private final SigningUseCase keys;
    private final int batchSize;

    public SigningKeyPruner(SigningUseCase keys, int batchSize) {
        this.keys = keys;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${kairos.api.signing.prune-interval:60s}",
            initialDelayString = "${kairos.api.signing.prune-interval:60s}")
    public void prune() {
        try {
            keys.pruneExpired(batchSize);
        } catch (RuntimeException failure) {
            // No key material or database values; a subsequent scheduled pass retries.
            LOG.warn("Signing-key cleanup failed: {}", failure.getClass().getSimpleName());
        }
    }
}
