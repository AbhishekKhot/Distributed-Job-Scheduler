package dev.kairos.api.team.application;

import dev.kairos.security.SecretValue;
import java.util.Objects;

/**
 * A new team and its first request-signing key, which is shown only in this response (LLD section 3.4, API 8).
 *
 * @param team the team
 * @param signingKey the request-signing key; redacted in {@link #toString()}
 */
public record CreatedTeam(Team team, SecretValue signingKey) {

    /** Validates the components. */
    public CreatedTeam {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(signingKey, "signingKey");
    }

    /** Leaves the signing key out. */
    @Override
    public String toString() {
        return "CreatedTeam[team=" + team + ", signingKey=<redacted>]";
    }
}
