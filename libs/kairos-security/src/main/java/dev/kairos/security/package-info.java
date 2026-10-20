/**
 * Security primitives.
 *
 * <p>Argon2id password hashing ({@code password}), ES256 access tokens and refresh-token rotation ({@code token}), API
 * keys hashed with a pepper and compared in constant time ({@code apikey}), Tink keysets sealed by a key encryption key
 * ({@code keyset}), encryption of team secrets and sensitive outputs ({@code cipher}), request signing for targets
 * ({@code signing}) and the Spring wiring ({@code config}).
 *
 * <p>This package holds what they share: {@link dev.kairos.security.SecretValue}, the wrapper that keeps one-time
 * secrets out of {@code toString()}, and {@link dev.kairos.security.RandomTokens}, which draws every random value from
 * an injected {@link java.security.SecureRandom}.
 */
package dev.kairos.security;
