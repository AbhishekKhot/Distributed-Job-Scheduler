package dev.kairos.engine.lock;

import org.jspecify.annotations.Nullable;

/**
 * Redis failed, rejected a command, timed out or is not connected. Internal to this package: the adapters turn it into
 * an {@code UNAVAILABLE} result, so it never reaches a caller of the ports.
 */
final class RedisUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RedisUnavailableException(String message) {
        super(message);
    }

    RedisUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
