package dev.kairos.api.security;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Reads the state behind an access token: its login session and the user's current team (control database). */
public interface SessionLookup {

    /**
     * Reads a user's session state.
     *
     * @param user the token's user
     * @param sessionId the token's {@code sid}, the refresh-token family of the login
     * @param now the current time, to ignore expired sessions
     * @return the state, or empty if the user does not exist
     */
    Optional<SessionState> find(UserId user, UUID sessionId, Instant now);

    /**
     * What the session check needs.
     *
     * @param sessionActive whether the login still has an active, unexpired refresh session
     * @param userActive whether the user is {@code ACTIVE} (not disabled by an operator)
     * @param team the user's current team, or {@code null}
     */
    record SessionState(
            boolean sessionActive,
            boolean userActive,
            @Nullable TeamId team) {}
}
