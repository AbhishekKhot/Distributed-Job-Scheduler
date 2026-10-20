package dev.kairos.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.UserId;
import dev.kairos.security.SecurityFixtures;
import dev.kairos.security.token.RefreshResult.Reason;
import dev.kairos.security.token.RefreshResult.Rejected;
import dev.kairos.security.token.RefreshResult.Rotated;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
    private final InMemoryRefreshSessionStore store = new InMemoryRefreshSessionStore();
    private final RefreshTokenService refresh =
            new RefreshTokenService(store, TestIds.factory(clock), clock, SecurityFixtures.RANDOM, Duration.ofDays(30));
    private final UserId user = TestIds.userId();

    @Test
    void issuesARandomTokenAndStoresOnlyItsHash() {
        IssuedRefreshToken issued = refresh.issue(user, "curl/8", "10.0.0.1");
        String token = issued.token().reveal();

        assertThat(token).startsWith("rt_").hasSize(46).matches("rt_[A-Za-z0-9_-]{43}");
        assertThat(issued.session().tokenHash()).isEqualTo(RefreshTokenService.hash(token));
        assertThat(issued.session().status()).isEqualTo(RefreshSessionStatus.ACTIVE);
        assertThat(issued.session().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        assertThat(issued.session().userAgent()).isEqualTo("curl/8");
        assertThat(issued.session().ipAddress()).isEqualTo("10.0.0.1");
        assertThat(refresh.issue(user, null, null).token()).isNotEqualTo(issued.token());
        assertThat(issued.toString()).doesNotContain(token).doesNotContain("10.0.0.1");
    }

    @Test
    void rotationReplacesTheTokenWithinTheFamily() {
        IssuedRefreshToken first = refresh.issue(user, null, null);

        RefreshResult result = refresh.rotate(first.token().reveal());

        assertThat(result).isInstanceOf(Rotated.class);
        Rotated rotated = (Rotated) result;
        assertThat(rotated.previous().sessionId()).isEqualTo(first.session().sessionId());
        assertThat(rotated.issued().session().familyId())
                .isEqualTo(first.session().familyId());
        assertThat(rotated.issued().session().sessionId())
                .isNotEqualTo(first.session().sessionId());
        assertThat(store.status(first.session())).isEqualTo(RefreshSessionStatus.ROTATED);
        assertThat(refresh.rotate(rotated.issued().token().reveal())).isInstanceOf(Rotated.class);
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        IssuedRefreshToken first = refresh.issue(user, null, null);
        IssuedRefreshToken other = refresh.issue(user, null, null);
        Rotated rotated = (Rotated) refresh.rotate(first.token().reveal());

        assertThat(refresh.rotate(first.token().reveal())).isEqualTo(new Rejected(Reason.REUSED));

        assertThat(store.status(rotated.issued().session())).isEqualTo(RefreshSessionStatus.REVOKED);
        assertThat(refresh.rotate(rotated.issued().token().reveal())).isEqualTo(new Rejected(Reason.REVOKED));
        // Another login of the same user is untouched.
        assertThat(store.status(other.session())).isEqualTo(RefreshSessionStatus.ACTIVE);
    }

    @Test
    void losingARotationRaceCountsAsReuse() {
        IssuedRefreshToken first = refresh.issue(user, null, null);
        store.loseNextRotation = true;

        assertThat(refresh.rotate(first.token().reveal())).isEqualTo(new Rejected(Reason.REUSED));
        assertThat(store.status(first.session())).isEqualTo(RefreshSessionStatus.REVOKED);
    }

    @Test
    void expiredAndUnknownTokensAreRejected() {
        IssuedRefreshToken first = refresh.issue(user, null, null);

        assertThat(refresh.rotate("rt_" + "A".repeat(43))).isEqualTo(new Rejected(Reason.UNKNOWN));
        assertThat(refresh.rotate("not-a-token")).isEqualTo(new Rejected(Reason.UNKNOWN));
        assertThat(refresh.rotate("rt_" + "+".repeat(43))).isEqualTo(new Rejected(Reason.UNKNOWN));
        assertThat(refresh.rotate("xx_" + "A".repeat(43))).isEqualTo(new Rejected(Reason.UNKNOWN));
        clock.advance(Duration.ofDays(30));
        assertThat(refresh.rotate(first.token().reveal())).isEqualTo(new Rejected(Reason.EXPIRED));
    }

    @Test
    void logoutRevokesTheFamilyAndIgnoresUnknownTokens() {
        IssuedRefreshToken first = refresh.issue(user, null, null);
        Rotated rotated = (Rotated) refresh.rotate(first.token().reveal());

        assertThat(refresh.revoke(rotated.issued().token().reveal())).isTrue();
        assertThat(store.status(rotated.issued().session())).isEqualTo(RefreshSessionStatus.REVOKED);
        assertThat(store.status(first.session())).isEqualTo(RefreshSessionStatus.REVOKED);
        assertThat(refresh.revoke("rt_unknown")).isFalse();
    }

    @Test
    void passwordChangeRevokesOtherFamilies() {
        IssuedRefreshToken mine = refresh.issue(user, null, null);
        IssuedRefreshToken other = refresh.issue(user, null, null);

        assertThat(refresh.revokeOthers(user, mine.session().familyId())).isEqualTo(1);
        assertThat(store.status(mine.session())).isEqualTo(RefreshSessionStatus.ACTIVE);
        assertThat(store.status(other.session())).isEqualTo(RefreshSessionStatus.REVOKED);
        assertThat(refresh.revokeOthers(user, null)).isEqualTo(1);
    }

    @Test
    void truncatesLongUserAgents() {
        assertThat(refresh.issue(user, "a".repeat(300), null).session().userAgent())
                .hasSize(256);
    }

    @Test
    void sessionsCompareByValue() {
        RefreshSession session = refresh.issue(user, "ua", null).session();
        RefreshSession copy = new RefreshSession(
                session.sessionId(),
                session.user(),
                session.familyId(),
                session.tokenHash(),
                session.status(),
                session.createdAt(),
                session.expiresAt(),
                session.userAgent(),
                session.ipAddress());

        assertThat(copy).isEqualTo(session).hasSameHashCodeAs(session);
        assertThat(copy).isNotEqualTo(refresh.issue(user, "ua", null).session());
        assertThat(refresh.toString()).contains("PT720H");
    }

    @Test
    void theLifetimeMustBePositive() {
        assertThatThrownBy(() -> new RefreshTokenService(
                        store, TestIds.factory(clock), clock, SecurityFixtures.RANDOM, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
