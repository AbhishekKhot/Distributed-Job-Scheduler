package dev.kairos.security.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.RandomTokens;
import dev.kairos.security.SecretValue;
import dev.kairos.security.token.TokenVerification.Invalid;
import dev.kairos.security.token.TokenVerification.Reason;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Issues and verifies the API's ES256 access tokens (HLD section 7, LLD section 3.3).
 *
 * <p>A token is a compact JWS with header {@code alg=ES256}, {@code typ=JWT} and {@code kid}, and claims {@code iss},
 * {@code aud}, {@code sub} (user id), {@code team} (team id, absent for a user without a team), {@code sid} (session
 * id), {@code iat}, {@code exp} and {@code jti} (16 random bytes, base64url). Lifetime and clock skew come from
 * {@link Settings}; the defaults are 15 minutes and 30 seconds.
 *
 * <p>Verification is strict: the token length is capped before parsing; the algorithm must be ES256 (so {@code none},
 * HS256 and RS256 are refused before any key is touched); the {@code kid} must be a platform key; keys embedded in
 * the header ({@code jwk}, {@code jku}, {@code x5c}, {@code x5u}) are never used; the issuer and audience must match;
 * expiry and issue time are checked with the configured skew; and {@code sub}, {@code sid} and {@code jti} must be
 * present and well-formed.
 *
 * <p>The key ring can be replaced at run time ({@link #replaceKeys(JwtKeyRing)}) after a rotation. Thread-safe.
 */
public final class AccessTokenService {

    /** The longest token accepted for verification; real tokens are about 500 characters. */
    static final int MAX_TOKEN_LENGTH = 4096;

    private static final String TEAM_CLAIM = "team";
    private static final String SESSION_CLAIM = "sid";
    private static final int TOKEN_ID_BYTES = 16;

    private final Settings settings;
    private final Clock clock;
    private final SecureRandom random;
    private volatile JwtKeyRing keys;

    /**
     * Token settings.
     *
     * @param issuer the {@code iss} value, for example {@code https://kairos.internal}
     * @param audience the {@code aud} value, for example {@code kairos-api}
     * @param ttl the token lifetime
     * @param clockSkew the tolerance applied to {@code exp} and {@code iat}
     */
    public record Settings(String issuer, String audience, Duration ttl, Duration clockSkew) {

        /** Validates the settings. */
        public Settings {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(ttl, "ttl");
            Objects.requireNonNull(clockSkew, "clockSkew");
            if (issuer.isBlank() || audience.isBlank() || ttl.isNegative() || ttl.isZero() || clockSkew.isNegative()) {
                throw new IllegalArgumentException("access-token settings are invalid");
            }
        }
    }

    /**
     * Creates the service.
     *
     * @param keys the signing keys
     * @param settings issuer, audience, lifetime and skew
     * @param clock the time source
     * @param random the CSPRNG for token ids
     */
    public AccessTokenService(JwtKeyRing keys, Settings settings, Clock clock, SecureRandom random) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Issues a token.
     *
     * @param user the user
     * @param team the user's team, or {@code null} if the user has none
     * @param sessionId the refresh-token session
     * @return the token and its claims
     */
    public AccessToken issue(UserId user, @Nullable TeamId team, UUID sessionId) {
        JwtKeyRing ring = keys;
        // JWT times are whole seconds; truncating here keeps the returned claims equal to what verification reads.
        long now = clock.instant().getEpochSecond();
        long expires = now + settings.ttl().toSeconds();
        String tokenId = RandomTokens.base64Url(random, TOKEN_ID_BYTES);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(settings.issuer())
                .audience(settings.audience())
                .subject(user.toString())
                .jwtID(tokenId)
                .claim("iat", now)
                .claim("exp", expires)
                .claim(SESSION_CLAIM, sessionId.toString());
        if (team != null) {
            claims.claim(TEAM_CLAIM, team.toString());
        }
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT)
                .keyID(ring.primaryKid())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(ring.signer());
        } catch (JOSEException e) {
            throw new IllegalStateException("access-token signing failed", e);
        }
        AccessTokenClaims issued = new AccessTokenClaims(
                user, team, sessionId, tokenId, Instant.ofEpochSecond(now), Instant.ofEpochSecond(expires));
        return new AccessToken(SecretValue.of(jwt.serialize()), issued);
    }

    /**
     * Verifies a token.
     *
     * @param token the bearer credential
     * @return the claims, or the reason for rejection
     */
    public TokenVerification verify(String token) {
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return new Invalid(Reason.MALFORMED);
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            return new Invalid(Reason.MALFORMED);
        }
        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())) {
            return new Invalid(Reason.ALGORITHM);
        }
        String kid = header.getKeyID();
        Optional<ECDSAVerifier> verifier = kid == null ? Optional.empty() : keys.verifier(kid);
        if (verifier.isEmpty()) {
            return new Invalid(Reason.UNKNOWN_KEY);
        }
        try {
            if (!jwt.verify(verifier.get())) {
                return new Invalid(Reason.SIGNATURE);
            }
        } catch (JOSEException e) {
            return new Invalid(Reason.SIGNATURE);
        }
        try {
            return checkClaims(jwt.getJWTClaimsSet());
        } catch (ParseException | ValidationFailedException | IllegalArgumentException e) {
            return new Invalid(Reason.CLAIMS);
        }
    }

    private TokenVerification checkClaims(JWTClaimsSet claims) throws ParseException {
        if (!settings.issuer().equals(claims.getIssuer())) {
            return new Invalid(Reason.ISSUER);
        }
        List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(settings.audience())) {
            return new Invalid(Reason.AUDIENCE);
        }
        // Nimbus parses the registered time claims into dates; only the epoch seconds are used.
        Long issuedAt = epochSeconds(claims.getDateClaim("iat"));
        Long expiresAt = epochSeconds(claims.getDateClaim("exp"));
        String subject = claims.getSubject();
        String session = claims.getStringClaim(SESSION_CLAIM);
        String tokenId = claims.getJWTID();
        String team = claims.getStringClaim(TEAM_CLAIM);
        if (issuedAt == null || expiresAt == null || subject == null || session == null || tokenId == null) {
            return new Invalid(Reason.CLAIMS);
        }
        long now = clock.instant().getEpochSecond();
        long skew = settings.clockSkew().toSeconds();
        if (now >= expiresAt + skew) {
            return new Invalid(Reason.EXPIRED);
        }
        if (issuedAt > now + skew) {
            return new Invalid(Reason.NOT_YET_VALID);
        }
        return new TokenVerification.Valid(new AccessTokenClaims(
                UserId.parse(subject),
                team == null ? null : TeamId.parse(team),
                UUID.fromString(session),
                tokenId,
                Instant.ofEpochSecond(issuedAt),
                Instant.ofEpochSecond(expiresAt)));
    }

    private static @Nullable Long epochSeconds(java.util.@Nullable Date date) {
        return date == null ? null : date.toInstant().getEpochSecond();
    }

    /**
     * Returns the public keys as a JWK Set, the body of {@code GET /.well-known/jwks.json}.
     *
     * @return the JWK Set
     */
    public Map<String, Object> jwks() {
        return keys.jwks();
    }

    /**
     * Replaces the key ring, for example after the platform keyset was rotated.
     *
     * @param newKeys the new keys
     */
    public void replaceKeys(JwtKeyRing newKeys) {
        this.keys = Objects.requireNonNull(newKeys, "newKeys");
    }

    @Override
    public String toString() {
        return "AccessTokenService[" + keys + ", issuer=" + settings.issuer() + "]";
    }
}
