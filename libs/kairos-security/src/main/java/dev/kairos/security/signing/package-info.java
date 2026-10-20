/**
 * Request signing.
 *
 * <p>{@link dev.kairos.security.signing.RequestSigner} adds {@code X-Kairos-Timestamp} and {@code X-Kairos-Signature}
 * (HMAC-SHA256 over method, path, timestamp, run id and body hash) to every target call;
 * {@link dev.kairos.security.signing.RequestSignatures} is the matching verifier for targets.
 */
package dev.kairos.security.signing;
