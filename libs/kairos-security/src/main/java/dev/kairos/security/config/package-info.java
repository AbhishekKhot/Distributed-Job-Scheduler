/**
 * Spring wiring of the security library.
 *
 * <p>Services import only what they need, with {@code @Import}; nothing is auto-configured.
 * {@link dev.kairos.security.config.PasswordConfiguration} (API, maintenance),
 * {@link dev.kairos.security.config.ApiKeyConfiguration} (API),
 * {@link dev.kairos.security.config.KeyManagementConfiguration} (API, executor, migrations),
 * {@link dev.kairos.security.config.PlatformKeysetConfiguration} (migrations bootstrap) and
 * {@link dev.kairos.security.config.TokenConfiguration} (API). All bind
 * {@link dev.kairos.security.config.SecurityProperties} ({@code kairos.security.*}).
 */
package dev.kairos.security.config;
