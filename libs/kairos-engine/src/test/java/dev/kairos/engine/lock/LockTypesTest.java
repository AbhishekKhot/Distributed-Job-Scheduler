package dev.kairos.engine.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import dev.kairos.testsupport.TestIds;
import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Tokens, keys and result types of the run lock and sent markers. */
class LockTypesTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void tokenEncodesFenceNodeAndRandomBytes() {
        LockToken token = LockToken.generate(new Fence(42), "executor-7.pod_a", RANDOM);

        assertThat(token.value()).matches("^42:executor-7\\.pod_a:[0-9a-f]{32}$");
        assertThat(LockToken.parse(token.value())).contains(token);
        assertThat(LockToken.generate(new Fence(42), "executor-7.pod_a", RANDOM))
                .isNotEqualTo(token);
    }

    @Test
    void parseRejectsMalformedTokens() {
        String nonce = "0123456789abcdef0123456789abcdef";

        assertThat(LockToken.parse("1:node:" + nonce)).isPresent();
        assertThat(LockToken.parse("1:node:" + nonce.toUpperCase(java.util.Locale.ROOT)))
                .isEmpty();
        assertThat(LockToken.parse("-1:node:" + nonce)).isEmpty();
        assertThat(LockToken.parse("9999999999:node:" + nonce)).isEmpty();
        assertThat(LockToken.parse("1:no:de:" + nonce)).isEmpty();
        assertThat(LockToken.parse("1::" + nonce)).isEmpty();
        assertThat(LockToken.parse("1:node:" + nonce + "00")).isEmpty();
        assertThat(LockToken.parse("1:" + "n".repeat(500) + ":" + nonce)).isEmpty();
    }

    @Test
    void nodeIdsAreValidated() {
        assertThat(LockToken.requireNodeId("a")).isEqualTo("a");
        assertThat(LockToken.requireNodeId("n".repeat(64))).hasSize(64);
        assertThatThrownBy(() -> LockToken.requireNodeId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LockToken.requireNodeId("n".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LockToken.requireNodeId("node:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LockToken.requireNodeId("node 1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockToken(new Fence(1), "node", "xyz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysFollowTheLld() {
        RunId runId = TestIds.runId(77);

        assertThat(RunLock.key(runId)).isEqualTo("kairos:lock:run:" + runId.value());
        assertThat(new SentMarker(runId, 3, 0).key()).isEqualTo("kairos:sent:" + runId.value() + ":3:0");
    }

    @Test
    void sentMarkerAttemptsAreRangeChecked() {
        RunId runId = TestIds.runId(1);

        assertThat(new SentMarker(runId, SentMarker.MAX_ATTEMPT, SentMarker.MAX_ATTEMPT).attempt())
                .isEqualTo(32767);
        assertThatThrownBy(() -> new SentMarker(runId, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentMarker(runId, 32768, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentMarker(runId, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentMarker(runId, 1, 32768)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SentMarkerPort.TTL.toHours()).isEqualTo(48);
    }

    @Test
    void resultTypesReportTheirOutcome() {
        RunLock lock = new RunLock(TestIds.runId(2), LockToken.generate(Fence.INITIAL, "node", RANDOM));

        assertThat(new AcquireResult.Acquired(lock).outcome()).isEqualTo(AcquireResult.Outcome.ACQUIRED);
        assertThat(new AcquireResult.Held().outcome()).isEqualTo(AcquireResult.Outcome.HELD);
        assertThat(new AcquireResult.Unavailable().outcome()).isEqualTo(AcquireResult.Outcome.UNAVAILABLE);
        assertThat(AcquireResult.Outcome.UNAVAILABLE.label()).isEqualTo("unavailable");
        assertThat(new RemainingResult.Owned(Duration.ofSeconds(3)).timeLeft()).contains(Duration.ofSeconds(3));
        assertThat(new RemainingResult.Unavailable().timeLeft()).isEmpty();
        assertThat(new RemainingResult.NotOwned().timeLeft()).isEmpty();
        assertThatThrownBy(() -> new RemainingResult.Owned(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LockInspection.Locked("not a token").token()).isEmpty();
        assertThat(new LockInspection.Locked(lock.token().value()).token()).contains(lock.token());
    }
}
