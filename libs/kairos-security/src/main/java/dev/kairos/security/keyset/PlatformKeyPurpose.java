package dev.kairos.security.keyset;

/** What a platform keyset is for; the {@code purpose} column of {@code platform_keysets} (LLD section 2.2). */
public enum PlatformKeyPurpose {
    /** ECDSA P-256 keys that sign access tokens (ES256). */
    JWT_SIGNING
}
