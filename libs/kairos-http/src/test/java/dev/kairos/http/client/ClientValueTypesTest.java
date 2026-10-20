package dev.kairos.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.retry.CallResult;
import dev.kairos.http.request.HttpMethod;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientValueTypesTest {

    private static final Instant T = Instant.parse("2026-10-20T10:00:00Z");

    @Test
    void parsesCaBundles() {
        TestPki.Issued ca = TestPki.ca("Bundle CA");
        TestPki.Issued other = TestPki.ca("Other CA");
        String pem = TestPki.pem(ca.certificate()) + TestPki.pem(other.certificate());

        CaBundle bundle = CaBundle.parsePem("team/ca", pem);

        assertThat(bundle.certificates()).hasSize(2);
        assertThat(bundle.id()).startsWith("team/ca#").hasSize("team/ca#".length() + 64);
        assertThat(bundle).isEqualTo(CaBundle.parsePem("team/ca", pem));
        assertThat(bundle).hasSameHashCodeAs(CaBundle.parsePem("team/ca", pem));
        assertThat(bundle).isNotEqualTo(CaBundle.parsePem("other/ca", pem));
        assertThat(bundle.toString()).contains("certificates=2");
    }

    @Test
    void rejectsBadCaBundles() {
        String pem = TestPki.pem(TestPki.ca("CA").certificate());

        assertThatThrownBy(() -> CaBundle.parsePem("team/ca", "not a certificate"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaBundle.parsePem("team/ca", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaBundle.parsePem("", pem)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaBundle.parsePem("team/ca", pem.repeat(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesSettings() {
        assertThat(HttpClientSettings.defaults().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThatThrownBy(() -> new HttpClientSettings(Duration.ZERO, Duration.ofSeconds(1), 10, 5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpClientSettings(Duration.ofSeconds(1), Duration.ofMinutes(1), 10, 5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpClientSettings(Duration.ofSeconds(1), Duration.ofSeconds(1), 10, 11, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpClientSettings(Duration.ofSeconds(1), Duration.ofSeconds(1), 10, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outcomesSayWhetherTheRequestWasSent() {
        HttpCallOutcome timedOutSent = new HttpCallOutcome.Failed(new CallResult.TimedOut(true), "t", T, T);
        HttpCallOutcome connectFailed = new HttpCallOutcome.Failed(new CallResult.ConnectionFailed(false), "c", T, T);
        HttpCallOutcome refused = new HttpCallOutcome.Failed(new CallResult.TargetNotAllowed(), "r", T, T);
        HttpCallOutcome tooLarge = new HttpCallOutcome.TooLarge(200, List.of(), T, T);

        assertThat(timedOutSent.requestSent()).isTrue();
        assertThat(connectFailed.requestSent()).isFalse();
        assertThat(refused.requestSent()).isFalse();
        assertThat(tooLarge.requestSent()).isTrue();
        assertThat(tooLarge.startedAt()).isEqualTo(T);
        assertThat(tooLarge.finishedAt()).isEqualTo(T);
        assertThat(new HttpCallOutcome.NotSent("vetoed", T, T).requestSent()).isFalse();
    }

    @Test
    void outcomesValidateTheirParts() {
        assertThatThrownBy(() -> new HttpCallOutcome.Failed(new CallResult.Responded(200), "x", T, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpCallOutcome.Received(99, List.of(), ResponseBody.empty(), T, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpCallOutcome.TooLarge(600, List.of(), T, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(HttpCallOutcome.isValidStatus(599)).isTrue();
    }

    @Test
    void sendContextAndVetoKeepTheirParts() {
        SendContext context = new SendContext(HttpMethod.PUT, "orders.internal", 443);
        SendVetoedException veto = new SendVetoedException("no time left", new IllegalStateException());

        assertThat(context.port()).isEqualTo(443);
        assertThat(veto).hasMessage("no time left").hasCauseInstanceOf(IllegalStateException.class);
    }
}
