package dev.kairos.domain.template;

import java.util.Objects;

/**
 * An extracted value; callers seal sensitive values before persistence or Kafka transport.
 * @param value the JSON value
 * @param sensitive whether it carries sensitive response data
 */
public record OutputValue(JsonValue value, boolean sensitive) {
    /** Requires an actual JSON value; JSON null uses the null singleton. */
    public OutputValue {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return "OutputValue[sensitive=" + sensitive + ", value=<redacted>]";
    }
}
