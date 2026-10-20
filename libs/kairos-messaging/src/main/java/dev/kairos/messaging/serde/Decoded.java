package dev.kairos.messaging.serde;

import java.util.Objects;

/**
 * The outcome of decoding one Kafka record value: the message, or a poison marker when the bytes are not a valid
 * message. Decoding never throws, so a bad record cannot stop a consumer or block its partition; the consumer counts and
 * skips poison records ({@code dev.kairos.messaging.client.PoisonMessages}).
 *
 * @param <T> the message type
 */
public sealed interface Decoded<T> permits Decoded.Valid, Decoded.Poison {

    /**
     * A decoded message.
     *
     * @param message the message
     * @param <T> the message type
     */
    record Valid<T>(T message) implements Decoded<T> {

        /** Checks the message is present. */
        public Valid {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * A record value that is not a valid message. It never holds the payload, so it can be logged safely.
     *
     * @param reason why the value was rejected
     * @param size the size of the value in bytes, 0 when it was null
     * @param <T> the expected message type
     */
    record Poison<T>(PoisonReason reason, int size) implements Decoded<T> {

        /** Checks the reason is present and the size is not negative. */
        public Poison {
            Objects.requireNonNull(reason, "reason");
            if (size < 0) {
                throw new IllegalArgumentException("The size must not be negative");
            }
        }
    }
}
