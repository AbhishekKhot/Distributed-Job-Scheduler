package dev.kairos.security.keyset;

/**
 * What a team keyset is for; the {@code purpose} column of {@code team_keysets} (LLD section 2.2).
 *
 * <p>Each purpose has its own keyset, so a key that encrypts target credentials never encrypts workflow outputs or
 * signs requests.
 */
public enum KeyPurpose {
    /** AES-256-GCM keys that encrypt the team's target credentials ({@code team_secrets}). */
    SECRETS,
    /** AES-256-GCM keys that seal sensitive workflow outputs in messages and {@code task_outputs}. */
    OUTPUTS,
    /** HMAC-SHA256 keys that sign requests to targets ({@code X-Kairos-Signature}). */
    SIGNING
}
