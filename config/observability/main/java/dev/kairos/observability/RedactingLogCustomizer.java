package dev.kairos.observability;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Redacts every string written into a structured (ECS) log line: the message, MDC and key-value
 * pairs, and the exception message and stack trace.
 *
 * <p>A member whose name is a sensitive key (see {@link LogRedactor#isSensitiveKey}) is masked
 * whole; every other string value goes through {@link LogRedactor#redact}. Spring Boot creates
 * this class from the {@code logging.structured.json.customizer} property, which
 * {@link ObservabilityEnvironmentPostProcessor} sets by default for every Kairos application.
 *
 * <p>Stateless and thread-safe.
 */
public final class RedactingLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    /** Creates the customizer; called reflectively by Spring Boot's logging system. */
    public RedactingLogCustomizer() {}

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(new RedactingValueProcessor());
    }

    private static final class RedactingValueProcessor implements ValueProcessor<@Nullable Object> {

        @Override
        public @Nullable Object processValue(JsonWriter.MemberPath path, @Nullable Object value) {
            if (!(value instanceof CharSequence text)) {
                return value;
            }
            if (LogRedactor.isSensitiveKey(path.name())) {
                return LogRedactor.MASK;
            }
            String redacted = LogRedactor.redact(text.toString());
            return redacted == null ? value : redacted;
        }
    }
}
