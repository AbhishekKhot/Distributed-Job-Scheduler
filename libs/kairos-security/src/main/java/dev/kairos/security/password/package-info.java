/**
 * Passwords.
 *
 * <p>{@link dev.kairos.security.password.PasswordHasher} hashes with Argon2id (19 MiB, 2 iterations, parallelism 1,
 * 16-byte salt, 32-byte hash) into PHC strings and verifies in constant time;
 * {@link dev.kairos.security.password.PasswordPolicy} enforces the signup and password-change rules of LLD section 3.3.
 */
package dev.kairos.security.password;
