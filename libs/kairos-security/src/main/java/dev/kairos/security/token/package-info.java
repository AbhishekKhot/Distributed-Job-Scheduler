/**
 * Access and refresh tokens.
 *
 * <p>{@link dev.kairos.security.token.AccessTokenService} issues and verifies 15-minute ES256 JWTs signed with the
 * platform keyset ({@link dev.kairos.security.token.JwtKeyRing}) and exports the JWKS;
 * {@link dev.kairos.security.token.RefreshTokenService} issues 30-day refresh tokens stored as SHA-256 hashes and
 * rotates them in families, revoking a family when a used token comes back.
 */
package dev.kairos.security.token;
