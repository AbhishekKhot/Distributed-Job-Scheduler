package dev.kairos.api.team;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.token.TokenVerification;
import dev.kairos.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Acceptance of T-203a: a removed member's access token is rejected within 30s, long before it expires. The service
 * clock is a {@link MutableClock}, so the session cache (which takes its time from the clock) is aged by exactly 30s
 * instead of the test sleeping.
 */
@Import(TeamSessionExpiryIntegrationTest.FrozenClock.class)
class TeamSessionExpiryIntegrationTest extends ApiApplicationTest {

    @Autowired
    private MutableClock clock;

    @Test
    void aRemovedMembersAccessTokenIsRejectedWithin30sWithoutWaitingForItsExpiry() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller remover = newUser(team);
        Caller removed = newUser(team);
        // Caches the removed member's session state, as a busy client would.
        assertThat(send("GET", "/v1/team", removed.token(), null).statusCode()).isEqualTo(200);

        assertThat(send("DELETE", "/v1/team/members/" + removed.user().value(), remover.token(), null)
                        .statusCode())
                .isEqualTo(204);
        // Still inside the cache lifetime: the bound is 30s, not immediate.
        assertThat(send("GET", "/v1/team", removed.token(), null).statusCode()).isEqualTo(200);

        clock.advance(Duration.ofSeconds(30));

        assertThat(send("GET", "/v1/team", removed.token(), null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/v1/team/members", removed.token(), null).statusCode())
                .isEqualTo(401);
        // The token itself is still valid for many minutes; only the session check refuses it.
        assertThat(tokens.verify(removed.token())).isInstanceOf(TokenVerification.Valid.class);
        assertThat(send("GET", "/v1/team", remover.token(), null).statusCode()).isEqualTo(200);
    }

    /** Replaces the service clock with one the test moves. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FrozenClock {

        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(Instant.now());
        }
    }
}
