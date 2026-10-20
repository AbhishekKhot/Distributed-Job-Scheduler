package dev.kairos.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.id.TeamId;
import dev.kairos.security.apikey.ApiKeyPeppers;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import dev.kairos.security.config.SecurityProperties;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import dev.kairos.security.keyset.NewSigningKey;
import dev.kairos.security.keyset.PlatformKeyPurpose;
import dev.kairos.security.password.Argon2idParameters;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.token.AccessToken;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.InMemoryRefreshSessionStore;
import dev.kairos.security.token.IssuedRefreshToken;
import dev.kairos.security.token.JwtKeyRing;
import dev.kairos.security.token.RefreshTokenService;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every type that carries a secret keeps it out of {@code toString()}. */
class NoSecretInToStringTest {

    @Test
    void noSecretValueAppearsInAnyToString() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
        String localKey = SecurityFixtures.randomHexKey();
        String pepper = SecurityFixtures.randomHexKey();
        LocalKeyEncryptionKey kek = LocalKeyEncryptionKey.forLocalProfile(localKey, true);
        KeysetService keysets = SecurityFixtures.keysetService(kek);
        TeamId team = TestIds.teamId();

        ApiKeyService apiKeys = new ApiKeyService(ApiKeyPeppers.of(1, pepper, null, null), SecurityFixtures.RANDOM);
        IssuedApiKey apiKey = apiKeys.issue();
        NewSigningKey signingKey = keysets.createSigningKey(team);
        JwtKeyRing ring = JwtKeyRing.fromKeyset(
                keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, keysets.createJwtSigningKeyset()));
        AccessTokenService tokens = new AccessTokenService(
                ring,
                new AccessTokenService.Settings("kairos", "kairos-api", Duration.ofMinutes(15), Duration.ofSeconds(30)),
                clock,
                SecurityFixtures.RANDOM);
        AccessToken accessToken = tokens.issue(TestIds.userId(), team, UUID.randomUUID());
        IssuedRefreshToken refreshToken = new RefreshTokenService(
                        new InMemoryRefreshSessionStore(),
                        TestIds.factory(clock),
                        clock,
                        SecurityFixtures.RANDOM,
                        Duration.ofDays(30))
                .issue(TestIds.userId(), null, null);
        PasswordHasher hasher = new PasswordHasher(Argon2idParameters.DEFAULT, SecurityFixtures.RANDOM);
        SecurityProperties.Kek kekProperties = new SecurityProperties.Kek(null, localKey);
        SecurityProperties.ApiKey apiKeyProperties = new SecurityProperties.ApiKey(pepper, 2, pepper, 1);

        List<String> secrets = List.of(
                localKey,
                pepper,
                apiKey.key().reveal(),
                apiKey.key().reveal().substring(24),
                HexFormat.of().formatHex(apiKey.secretHash()),
                signingKey.exportedKey().reveal(),
                signingKey.exportedKey().reveal().substring(8),
                accessToken.token().reveal(),
                refreshToken.token().reveal(),
                refreshToken.token().reveal().substring(3));
        List<Object> carriers = new ArrayList<>(List.of(
                kek,
                keysets,
                apiKeys,
                apiKey,
                apiKeys.parse(apiKey.key().reveal()).orElseThrow(),
                signingKey,
                signingKey.sealed(),
                keysets.signingKey(team, signingKey.sealed()),
                keysets.aead(team, KeyPurpose.SECRETS, keysets.createTeamKeyset(team, KeyPurpose.SECRETS)),
                ring,
                tokens,
                accessToken,
                refreshToken,
                refreshToken.session(),
                hasher,
                kekProperties,
                apiKeyProperties));
        carriers.add(apiKey.key());

        for (Object carrier : carriers) {
            String text = carrier.toString();
            for (String secret : secrets) {
                assertThat(text).as(carrier.getClass().getSimpleName()).doesNotContain(secret);
            }
        }
    }
}
