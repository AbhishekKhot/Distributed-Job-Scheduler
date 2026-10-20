package dev.kairos.security.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.SecurityFixtures;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.NewSigningKey;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class RequestSignerTest {

    private static final byte[] BODY = "{\"orderId\":42}".getBytes(StandardCharsets.UTF_8);

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
    private final KeysetService keysets = SecurityFixtures.keysetService();
    private final TeamId team = TestIds.teamId();
    private final NewSigningKey v1 = keysets.createSigningKey(team);
    private final RequestSigner signer = new RequestSigner(clock);
    private final RunId run = TestIds.runId(3);

    @Test
    void signsTheDocumentedCanonicalString() throws Exception {
        SignatureHeaders headers =
                signer.sign(keysets.signingKey(team, v1.sealed()), "post", "/v1/orders?x=1", run, BODY);

        assertThat(headers.timestamp()).isEqualTo(Long.toString(clock.instant().getEpochSecond()));
        String canonical = "POST\n/v1/orders?x=1\n" + headers.timestamp() + "\n" + run + "\n"
                + HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(BODY));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                java.util.Base64.getUrlDecoder()
                        .decode(v1.exportedKey().reveal().substring("ksig_v1_".length())),
                "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(headers.signature()).isEqualTo("v1=" + expected);
    }

    @Test
    void aTargetVerifiesWithTheExportedKey() {
        SignatureHeaders headers = signer.sign(keysets.signingKey(team, v1.sealed()), "POST", "/v1/orders", run, BODY);
        RequestSignatures verifier =
                RequestSignatures.of(List.of(v1.exportedKey().reveal()), clock, Duration.ofMinutes(5));

        assertThat(verifier.verify(
                        "POST", "/v1/orders", run.toString(), BODY, headers.timestamp(), headers.signature()))
                .isTrue();
        assertThat(verifier.verify("PUT", "/v1/orders", run.toString(), BODY, headers.timestamp(), headers.signature()))
                .isFalse();
        assertThat(verifier.verify("POST", "/v1/order", run.toString(), BODY, headers.timestamp(), headers.signature()))
                .isFalse();
        assertThat(verifier.verify(
                        "POST",
                        "/v1/orders",
                        TestIds.runId(3).toString(),
                        BODY,
                        headers.timestamp(),
                        headers.signature()))
                .isFalse();
        assertThat(verifier.verify(
                        "POST", "/v1/orders", run.toString(), new byte[0], headers.timestamp(), headers.signature()))
                .isFalse();
        assertThat(verifier.verify(
                        "POST",
                        "/v1/orders",
                        run.toString(),
                        BODY,
                        Long.toString(clock.instant().getEpochSecond() + 1),
                        headers.signature()))
                .isFalse();
        assertThat(verifier.toString()).contains("[1]").doesNotContain("ksig");
    }

    @Test
    void staleSignaturesAreRejected() {
        SignatureHeaders headers = signer.sign(keysets.signingKey(team, v1.sealed()), "GET", "/ping", run, new byte[0]);
        RequestSignatures verifier =
                RequestSignatures.of(List.of(v1.exportedKey().reveal()), clock, Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(5));
        assertThat(verifier.verify(
                        "GET", "/ping", run.toString(), new byte[0], headers.timestamp(), headers.signature()))
                .isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(verifier.verify(
                        "GET", "/ping", run.toString(), new byte[0], headers.timestamp(), headers.signature()))
                .isFalse();
    }

    @Test
    void afterRotationTheNewVersionSignsAndTargetsAcceptBoth() {
        SignatureHeaders old = signer.sign(keysets.signingKey(team, v1.sealed()), "GET", "/ping", run, new byte[0]);
        NewSigningKey v2 = keysets.rotateSigningKey(team, v1.sealed());
        SignatureHeaders fresh = signer.sign(keysets.signingKey(team, v2.sealed()), "GET", "/ping", run, new byte[0]);
        RequestSignatures both = RequestSignatures.of(
                List.of(v1.exportedKey().reveal(), v2.exportedKey().reveal()), clock, Duration.ofMinutes(5));
        RequestSignatures onlyNew =
                RequestSignatures.of(List.of(v2.exportedKey().reveal()), clock, Duration.ofMinutes(5));

        assertThat(fresh.signature()).startsWith("v2=");
        assertThat(both.verify("GET", "/ping", run.toString(), new byte[0], old.timestamp(), old.signature()))
                .isTrue();
        assertThat(both.verify("GET", "/ping", run.toString(), new byte[0], fresh.timestamp(), fresh.signature()))
                .isTrue();
        assertThat(onlyNew.verify("GET", "/ping", run.toString(), new byte[0], old.timestamp(), old.signature()))
                .isFalse();
    }

    @Test
    void malformedHeadersFailVerificationWithoutExceptions() {
        RequestSignatures verifier =
                RequestSignatures.of(List.of(v1.exportedKey().reveal()), clock, Duration.ofMinutes(5));
        String now = Long.toString(clock.instant().getEpochSecond());

        for (String bad :
                new String[] {"", "v1", "x1=00", "v=00", "vx=00", "v1=zz", "v1234567=00", "v1=" + "0".repeat(200)}) {
            assertThat(verifier.verify("GET", "/", "r", new byte[0], now, bad)).isFalse();
        }
        assertThat(verifier.verify("GET", "/", "r", new byte[0], "soon", "v1=00"))
                .isFalse();
        assertThat(verifier.verify("GET", "/", "r", new byte[0], "9".repeat(25), "v1=00"))
                .isFalse();
        assertThat(verifier.verify("GET", "bad path", "r", new byte[0], now, "v1=00"))
                .isFalse();
    }

    @Test
    void refusesInputThatCouldForgeTheCanonicalForm() {
        var key = keysets.signingKey(team, v1.sealed());

        assertThatThrownBy(() -> signer.sign(key, "GET\nX", "/a", run, BODY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign(key, "", "/a", run, BODY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign(key, "GET", "/a\r\nb", run, BODY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign(key, "GET", "a", run, BODY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign(key, "GET", "/café", run, BODY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMalformedExportedKeys() {
        String valid = v1.exportedKey().reveal();

        for (String bad :
                new String[] {"ksig_v1", "key_v1_abc", "ksig_vx_" + valid.substring(8), "ksig_v1_!!!", "ksig_v1_AAAA"
                }) {
            assertThatThrownBy(() -> RequestSignatures.of(List.of(bad), clock, Duration.ofMinutes(5)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RequestSignatures.of(List.of(valid, valid), clock, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
