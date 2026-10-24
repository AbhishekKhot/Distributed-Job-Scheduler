package dev.kairos.executor.consume.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalDispatchLimiterTest {

    @Test
    void enforcesTeamAndEndpointLimitsAndReleasesBothTogether() {
        LocalDispatchLimiter limiter = new LocalDispatchLimiter();
        UUID team = UUID.randomUUID();
        UUID firstEndpoint = UUID.randomUUID();
        UUID secondEndpoint = UUID.randomUUID();

        assertThat(limiter.acquire(team, firstEndpoint, new DispatchLimits(1, 3)))
                .isEqualTo(LocalDispatchLimiter.Result.ACQUIRED);
        assertThat(limiter.acquire(team, secondEndpoint, new DispatchLimits(1, 3)))
                .isEqualTo(LocalDispatchLimiter.Result.TEAM_LIMIT);
        assertThat(limiter.acquire(UUID.randomUUID(), firstEndpoint, new DispatchLimits(2, 1)))
                .isEqualTo(LocalDispatchLimiter.Result.ENDPOINT_LIMIT);

        limiter.release(team, firstEndpoint);
        assertThat(limiter.acquire(team, secondEndpoint, new DispatchLimits(1, 3)))
                .isEqualTo(LocalDispatchLimiter.Result.ACQUIRED);
    }
}
