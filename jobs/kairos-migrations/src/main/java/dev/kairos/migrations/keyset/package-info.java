/**
 * Platform keyset bootstrap of the migrations job.
 *
 * <p>Wires the security library's {@link dev.kairos.security.config.PlatformKeysetConfiguration} to the control
 * database, so the job can create the access-token signing keyset the API needs before it starts.
 */
package dev.kairos.migrations.keyset;
