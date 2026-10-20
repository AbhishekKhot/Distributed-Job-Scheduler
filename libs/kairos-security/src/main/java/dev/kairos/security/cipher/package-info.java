/**
 * Encryption of team secrets and sensitive outputs.
 *
 * <p>{@link dev.kairos.security.cipher.SecretCipher} uses the team's AES-256-GCM keysets with associated data that
 * binds each ciphertext to its team, name and version (secrets) or team, run and output name (outputs).
 */
package dev.kairos.security.cipher;
