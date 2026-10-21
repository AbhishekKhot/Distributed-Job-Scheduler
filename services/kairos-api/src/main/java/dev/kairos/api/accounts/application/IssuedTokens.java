package dev.kairos.api.accounts.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.security.SecretValue;
import dev.kairos.security.token.AccessToken;
import dev.kairos.security.token.IssuedRefreshToken;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The tokens of a login or refresh (LLD section 3.3, APIs 2 and 3). Both tokens are redacted in {@link #toString()}.
 *
 * @param accessToken the access token, whose {@code sid} is the refresh-token family
 * @param expiresInSeconds the access token's lifetime
 * @param refreshToken the refresh token, returned once
 * @param refreshExpiresInSeconds the refresh token's lifetime
 * @param team the team the access token carries, or {@code null}
 */
public record IssuedTokens(
        SecretValue accessToken,
        long expiresInSeconds,
        SecretValue refreshToken,
        long refreshExpiresInSeconds,
        @Nullable TeamId team) {

    /** Validates the components. */
    public IssuedTokens {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshToken, "refreshToken");
    }

    static IssuedTokens of(AccessToken access, IssuedRefreshToken refresh, @Nullable TeamId team) {
        long refreshSeconds = Duration.between(
                        refresh.session().createdAt(), refresh.session().expiresAt())
                .toSeconds();
        return new IssuedTokens(access.token(), access.expiresInSeconds(), refresh.token(), refreshSeconds, team);
    }
}
