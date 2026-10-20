package dev.kairos.http.redact;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.http.request.HeaderField;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedactorTest {

    private final Redactor redactor = new Redactor(List.of("X-Api-Key"));

    @Test
    void redactsSecretHeaders() {
        List<HeaderField> headers = List.of(
                new HeaderField("Authorization", "Bearer abc"),
                new HeaderField("cookie", "s=1"),
                new HeaderField("Set-Cookie", "s=2"),
                new HeaderField("x-api-key", "k"),
                new HeaderField("X-Custom-Key", "c"),
                new HeaderField("Content-Type", "application/json"));

        List<HeaderField> redacted = redactor.redactHeaders(headers, List.of("X-Custom-Key"));

        assertThat(redacted)
                .extracting(HeaderField::value)
                .containsExactly(
                        Redactor.REDACTED,
                        Redactor.REDACTED,
                        Redactor.REDACTED,
                        Redactor.REDACTED,
                        Redactor.REDACTED,
                        "application/json");
        assertThat(redactor.isSecretHeader("PROXY-AUTHORIZATION")).isTrue();
        assertThat(redactor.isSecretHeader("Accept")).isFalse();
    }

    @Test
    void removesKnownSecretsAndCredentialShapes() {
        String error = redactor.redactError(
                "upstream said: token sk-12345 rejected; header was Bearer eyJhbGciOi.payload.sig and Basic dXNlcjpwYXNz",
                List.of("sk-12345"));

        assertThat(error)
                .doesNotContain("sk-12345")
                .doesNotContain("eyJhbGciOi")
                .doesNotContain("dXNlcjpwYXNz")
                .contains("Bearer " + Redactor.REDACTED)
                .contains("Basic " + Redactor.REDACTED);
    }

    @Test
    void removesTheLongestSecretFirstAndBeforeTruncating() {
        String secret = "s".repeat(20);
        String message = "x".repeat(Redactor.MAX_ERROR_CHARS - 5) + secret;

        String error = redactor.redactError(message, List.of(secret, "ss", ""));

        assertThat(error).hasSizeLessThanOrEqualTo(Redactor.MAX_ERROR_CHARS).doesNotContain("ss");
    }

    @Test
    void replacesControlCharacters() {
        assertThat(redactor.redactError("line1\r\nline2\u0000", List.of())).isEqualTo("line1  line2 ");
    }

    @Test
    void snippetsAreRedactedAndCappedInUtf8Bytes() {
        String sensitive = "4111111111111111";
        byte[] body = ("{\"card\":\"" + sensitive + "\",\"pad\":\"" + "é".repeat(2000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        String snippet = redactor.snippet(body, List.of(sensitive));

        assertThat(snippet).doesNotContain(sensitive).contains(Redactor.REDACTED);
        assertThat(snippet.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(Redactor.MAX_SNIPPET_BYTES);
        assertThat(snippet).doesNotContain("�");
    }

    @Test
    void handlesInvalidUtf8AndHugeInput() {
        byte[] invalid = {(byte) 0xC3, (byte) 0x28, 'o', 'k'};
        assertThat(redactor.snippet(invalid, List.of())).endsWith("(ok");

        byte[] huge = new byte[200_000];
        java.util.Arrays.fill(huge, (byte) 'a');
        assertThat(redactor.snippet(huge, List.of())).hasSize(Redactor.MAX_SNIPPET_BYTES);
        assertThat(redactor.redactError("a".repeat(100_000), List.of())).hasSize(Redactor.MAX_ERROR_CHARS);
    }

    @Test
    void neverSplitsASurrogatePair() {
        String emoji = "😀";
        String message = "a".repeat(Redactor.MAX_ERROR_CHARS - 1) + emoji;

        String error = redactor.redactError(message, List.of());

        assertThat(error).hasSize(Redactor.MAX_ERROR_CHARS - 1);
        String snippet = redactor.snippet(("a".repeat(1022) + emoji).getBytes(StandardCharsets.UTF_8), List.of());
        assertThat(snippet).hasSize(1022);
    }
}
