/**
 * Target authentication.
 *
 * <p>{@link dev.kairos.http.auth.Authenticator} applies an endpoint's {@link dev.kairos.http.auth.AuthMaterial} (auth
 * settings plus the already-decrypted secret) with the {@link dev.kairos.http.auth.AuthApplier} strategy for its type:
 * {@code NONE}, {@code BASIC}, {@code BEARER}, {@code API_KEY} (header or query) or {@code OAUTH2_CLIENT_CREDENTIALS},
 * whose tokens {@link dev.kairos.http.auth.OAuth2TokenProvider} fetches through the same validating client and caches
 * per team, endpoint version and secret version until 60 seconds before expiry. No secret appears in any
 * {@code toString()}.
 */
package dev.kairos.http.auth;
