/**
 * Keysets and the key encryption key.
 *
 * <p>{@link dev.kairos.security.keyset.KeysetService} creates, rotates and opens Tink keysets: AES-256-GCM per team for
 * {@code SECRETS} and {@code OUTPUTS}, HMAC-SHA256 per team for request {@code SIGNING}, and the platform's ECDSA P-256
 * keyset for access tokens. Every keyset is sealed by a {@link dev.kairos.security.keyset.KeyEncryptionKey}: Cloud KMS
 * in production ({@link dev.kairos.security.keyset.GcpKmsKeyEncryptionKey}) and {@code KAIROS_LOCAL_KEK} under the
 * {@code local} profile only ({@link dev.kairos.security.keyset.LocalKeyEncryptionKey}).
 * {@link dev.kairos.security.keyset.PlatformKeysetBootstrap} creates the access-token keyset when it is missing.
 */
package dev.kairos.security.keyset;
