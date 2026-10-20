/**
 * Team API keys.
 *
 * <p>{@link dev.kairos.security.apikey.ApiKeyService} issues {@code kairos_<keyId>_<secret>} keys, stores only
 * HMAC-SHA256 of the secret under a versioned pepper ({@link dev.kairos.security.apikey.ApiKeyPeppers}), and verifies
 * presented keys in constant time, across a pepper rotation.
 */
package dev.kairos.security.apikey;
