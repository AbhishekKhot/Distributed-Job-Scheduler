package dev.kairos.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.RandomTokens;
import dev.kairos.security.SecurityFixtures;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.PlatformKeyPurpose;
import dev.kairos.security.keyset.SealedKeyset;
import dev.kairos.security.token.TokenVerification.Invalid;
import dev.kairos.security.token.TokenVerification.Reason;
import dev.kairos.security.token.TokenVerification.Valid;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessTokenServiceTest {

    private static final AccessTokenService.Settings SETTINGS =
            new AccessTokenService.Settings("kairos", "kairos-api", Duration.ofMinutes(15), Duration.ofSeconds(30));

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
    private final KeysetService keysets = SecurityFixtures.keysetService();
    private final SealedKeyset sealed = keysets.createJwtSigningKeyset();
    private final JwtKeyRing ring = ring(sealed);
    private final AccessTokenService tokens = new AccessTokenService(ring, SETTINGS, clock, SecurityFixtures.RANDOM);
    private final UserId user = TestIds.userId();
    private final TeamId team = TestIds.teamId();
    private final UUID session = UUID.randomUUID();

    private JwtKeyRing ring(SealedKeyset keyset) {
        return JwtKeyRing.fromKeyset(keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, keyset));
    }

    @Test
    void issuesAnEs256TokenThatVerifies() throws Exception {
        AccessToken token = tokens.issue(user, team, session);

        assertThat(token.expiresInSeconds()).isEqualTo(900);
        SignedJWT jwt = SignedJWT.parse(token.token().reveal());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(ring.primaryKid()).matches("k[0-9a-f]{8}");
        assertThat(jwt.getJWTClaimsSet().getClaims())
                .containsKeys("sub", "team", "sid", "iat", "exp", "jti", "iss", "aud");

        TokenVerification result = tokens.verify(token.token().reveal());
        assertThat(result).isInstanceOf(Valid.class);
        AccessTokenClaims claims = ((Valid) result).claims();
        assertThat(claims).isEqualTo(token.claims());
        assertThat(claims.teamId()).contains(team);
        assertThat(claims.sessionId()).isEqualTo(session);
        assertThat(claims.tokenId()).hasSize(22);
    }

    @Test
    void aUserWithoutATeamGetsNoTeamClaim() throws Exception {
        AccessToken token = tokens.issue(user, null, session);

        assertThat(SignedJWT.parse(token.token().reveal()).getJWTClaimsSet().getClaims())
                .doesNotContainKey("team");
        assertThat(((Valid) tokens.verify(token.token().reveal())).claims().teamId())
                .isEmpty();
    }

    @Test
    void expiresAfterTheTtlPlusTheSkew() {
        String token = tokens.issue(user, team, session).token().reveal();

        clock.advance(Duration.ofMinutes(15).plusSeconds(29));
        assertThat(tokens.verify(token)).isInstanceOf(Valid.class);
        clock.advance(Duration.ofSeconds(1));
        assertThat(tokens.verify(token)).isEqualTo(new Invalid(Reason.EXPIRED));
    }

    @Test
    void rejectsTokensIssuedInTheFuture() {
        String token = tokens.issue(user, team, session).token().reveal();

        clock.set(Instant.parse("2026-10-20T09:59:00Z"));
        assertThat(tokens.verify(token)).isEqualTo(new Invalid(Reason.NOT_YET_VALID));
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        KeysetService other = SecurityFixtures.keysetService();
        AccessTokenService foreign = new AccessTokenService(
                JwtKeyRing.fromKeyset(
                        other.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, other.createJwtSigningKeyset())),
                SETTINGS,
                clock,
                SecurityFixtures.RANDOM);
        String token = foreign.issue(user, team, session).token().reveal();

        assertThat(tokens.verify(token)).isEqualTo(new Invalid(Reason.UNKNOWN_KEY));
        // The same kid with a different key: forge the header to name the real kid.
        String[] parts = token.split("\\.", -1);
        String header = b64("{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"" + ring.primaryKid() + "\"}");
        assertThat(tokens.verify(header + "." + parts[1] + "." + parts[2])).isEqualTo(new Invalid(Reason.SIGNATURE));
    }

    @Test
    void rejectsAlgNone() {
        String payload = b64("{\"sub\":\"" + user + "\",\"iss\":\"kairos\",\"aud\":\"kairos-api\"}");
        String header = b64("{\"alg\":\"none\",\"kid\":\"" + ring.primaryKid() + "\"}");

        assertThat(tokens.verify(header + "." + payload + ".")).isInstanceOf(Invalid.class);
        assertThat(tokens.verify(header + "." + payload + "." + b64("sig"))).isInstanceOf(Invalid.class);
    }

    @Test
    void rejectsHs256EvenWithAKnownKid() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .keyID(ring.primaryKid())
                        .build(),
                validClaims().build());
        jwt.sign(new MACSigner(RandomTokens.bytes(SecurityFixtures.RANDOM, 32)));

        assertThat(tokens.verify(jwt.serialize())).isEqualTo(new Invalid(Reason.ALGORITHM));
    }

    @Test
    void rejectsWrongIssuerAudienceAndMissingClaims() {
        assertThat(sign(validClaims().issuer("someone-else"))).isEqualTo(new Invalid(Reason.ISSUER));
        assertThat(sign(validClaims().audience("other-api"))).isEqualTo(new Invalid(Reason.AUDIENCE));
        assertThat(sign(validClaims().audience((String) null))).isEqualTo(new Invalid(Reason.AUDIENCE));
        assertThat(sign(validClaims().jwtID(null))).isEqualTo(new Invalid(Reason.CLAIMS));
        assertThat(sign(validClaims().claim("sid", null))).isEqualTo(new Invalid(Reason.CLAIMS));
        assertThat(sign(validClaims().subject("not-a-user-id"))).isEqualTo(new Invalid(Reason.CLAIMS));
        assertThat(sign(validClaims().claim("team", "zzz"))).isEqualTo(new Invalid(Reason.CLAIMS));
        assertThat(sign(validClaims().claim("exp", "tomorrow"))).isEqualTo(new Invalid(Reason.CLAIMS));
        assertThat(sign(validClaims())).isInstanceOf(Valid.class);
    }

    @Test
    void rejectsMalformedInputAndMissingKid() throws Exception {
        assertThat(tokens.verify("")).isEqualTo(new Invalid(Reason.MALFORMED));
        assertThat(tokens.verify("a.b")).isEqualTo(new Invalid(Reason.MALFORMED));
        assertThat(tokens.verify("x".repeat(AccessTokenService.MAX_TOKEN_LENGTH + 1)))
                .isEqualTo(new Invalid(Reason.MALFORMED));
        SignedJWT noKid = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).build(), validClaims().build());
        noKid.sign(ring.signer());
        assertThat(tokens.verify(noKid.serialize())).isEqualTo(new Invalid(Reason.UNKNOWN_KEY));
    }

    @Test
    void tokensSignedBeforeARotationStayValid() {
        String before = tokens.issue(user, team, session).token().reveal();
        JwtKeyRing rotated = ring(keysets.rotateJwtSigningKeyset(sealed));

        tokens.replaceKeys(rotated);
        String after = tokens.issue(user, team, session).token().reveal();

        assertThat(tokens.verify(before)).isInstanceOf(Valid.class);
        assertThat(tokens.verify(after)).isInstanceOf(Valid.class);
        assertThat(rotated.primaryKid()).isNotEqualTo(ring.primaryKid());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys =
                (List<Map<String, Object>>) tokens.jwks().get("keys");
        assertThat(keys).hasSize(2);
    }

    @Test
    void theJwksHoldsPublicKeysOnly() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys =
                (List<Map<String, Object>>) tokens.jwks().get("keys");

        assertThat(keys).singleElement().satisfies(key -> {
            assertThat(key)
                    .containsEntry("kty", "EC")
                    .containsEntry("crv", "P-256")
                    .containsEntry("alg", "ES256");
            assertThat(key).containsEntry("use", "sig").containsEntry("kid", ring.primaryKid());
            assertThat(key).containsKeys("x", "y").doesNotContainKey("d");
        });
        assertThat(ring.toString()).contains(ring.primaryKid());
        assertThat(tokens.toString()).doesNotContain("\"d\"");
    }

    @Test
    void secretsNeverAppearInToString() {
        AccessToken token = tokens.issue(user, team, session);

        assertThat(token.toString()).doesNotContain(token.token().reveal());
        assertThat(token.toString()).doesNotContain(token.token().reveal().split("\\.", -1)[2]);
    }

    @Test
    void settingsAreValidated() {
        assertThatThrownBy(() -> new AccessTokenService.Settings(" ", "a", Duration.ofMinutes(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccessTokenService.Settings("i", "a", Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new AccessTokenService.Settings("i", "a", Duration.ofMinutes(1), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JWTClaimsSet.Builder validClaims() {
        long now = clock.instant().getEpochSecond();
        return new JWTClaimsSet.Builder()
                .issuer("kairos")
                .audience("kairos-api")
                .subject(user.toString())
                .jwtID("id")
                .claim("iat", now)
                .claim("exp", now + 60)
                .claim("sid", session.toString());
    }

    private TokenVerification sign(JWTClaimsSet.Builder claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .keyID(ring.primaryKid())
                            .build(),
                    claims.build());
            jwt.sign(ring.signer());
            return tokens.verify(jwt.serialize());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
