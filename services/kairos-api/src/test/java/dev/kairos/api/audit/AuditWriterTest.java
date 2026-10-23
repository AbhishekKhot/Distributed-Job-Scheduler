package dev.kairos.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.security.SecretValue;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Shared audit metadata rejects accidental secret objects and unsafe field names before persistence. */
class AuditWriterTest {
    @Test
    void copiesMetadataAndRedactsDiagnostics() {
        Map<String, Object> details = new HashMap<>();
        details.put("name", "job-name");
        details.put("keyVersion", 2);
        details.put("sessions", 4L);
        details.put("enabled", true);
        details.put("count", (short) 1);
        AuditWriter.Entry entry = entry(details);
        details.clear();
        assertThat(entry.details()).hasSize(5);
        assertThat(entry.toString()).doesNotContain("job-name");
        assertThatThrownBy(() -> entry.details().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsSecretsNestedObjectsAndOversizedMetadata() {
        for (Map<String, Object> details : java.util.List.<Map<String, Object>>of(
                Map.of("signingKey", "canary"),
                Map.of("PASSWORD", "canary"),
                Map.of("clientSecret", "canary"),
                Map.of("key", "canary"),
                Map.of("payload", SecretValue.of("canary")),
                Map.of("nested", Map.of("x", 1)),
                Map.of("name", "x".repeat(1025)),
                Map.of("bad-name", 1),
                Map.of("x".repeat(65), 1))) {
            assertThatThrownBy(() -> entry(details))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("canary");
        }
        Map<String, Object> large = new HashMap<>();
        for (int i = 0; i < 33; i++) large.put("x" + i, i);
        assertThatThrownBy(() -> entry(large)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesActorActionAndResourceLengths() {
        for (String actor : java.util.List.of("bad", "user:" + "x".repeat(160))) {
            assertThatThrownBy(() -> new AuditWriter.Entry(null, actor, "TEST", null, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String action : java.util.List.of("lowercase", "X".repeat(65))) {
            assertThatThrownBy(() -> new AuditWriter.Entry(null, "system:test", action, null, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AuditWriter.Entry(null, "system:test", "TEST", "x".repeat(65), null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditWriter.Entry(null, "system:test", "TEST", null, "x".repeat(129), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AuditWriter.Entry entry(Map<String, Object> details) {
        return new AuditWriter.Entry(null, "system:test", "TEST", null, null, details);
    }
}
