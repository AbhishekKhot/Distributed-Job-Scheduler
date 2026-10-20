package dev.kairos.messaging.serde;

import java.util.Locale;

/** Why a record value could not be decoded; also the {@code reason} label of {@code kairos_messages_poison_total}. */
public enum PoisonReason {
    /** The value was null or empty; every Kairos message carries at least its ids. */
    EMPTY,
    /** The value was larger than {@link MessageType#MAX_PAYLOAD_BYTES}. */
    TOO_LARGE,
    /** The value was not a valid Protobuf encoding of the expected message. */
    MALFORMED;

    /**
     * Returns the metric label value.
     *
     * @return the lower-case name, for example {@code too_large}
     */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
