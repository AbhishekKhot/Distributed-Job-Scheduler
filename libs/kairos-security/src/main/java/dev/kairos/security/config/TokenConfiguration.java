package dev.kairos.security.config;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.PlatformKeyPurpose;
import dev.kairos.security.keyset.PlatformKeysetStore;
import dev.kairos.security.keyset.SealedKeyset;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.JwtKeyRing;
import dev.kairos.security.token.RefreshSessionStore;
import dev.kairos.security.token.RefreshTokenService;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Access and refresh tokens. Imported by the API, which also provides the {@link RefreshSessionStore}.
 *
 * <p>The access-token keys are read from {@code platform_keysets} at startup. The API fails to start if the keyset is
 * missing, which means the migrations job has not run.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
@Import(PlatformKeysetConfiguration.class)
public class TokenConfiguration {

    /** Creates the configuration; Spring calls it. */
    public TokenConfiguration() {}

    /**
     * The access-token service.
     *
     * @param store the platform keyset store
     * @param keysets opens the keyset
     * @param properties the security configuration
     * @param clock the service's clock, if any
     * @param random the service's CSPRNG, if any
     * @return the service
     * @throws IllegalStateException if the access-token keyset does not exist
     */
    @Bean
    public AccessTokenService accessTokenService(
            PlatformKeysetStore store,
            KeysetService keysets,
            SecurityProperties properties,
            ObjectProvider<Clock> clock,
            ObjectProvider<SecureRandom> random) {
        SealedKeyset sealed = store.find(PlatformKeyPurpose.JWT_SIGNING)
                .orElseThrow(() -> new IllegalStateException(
                        "the access-token signing keyset is missing; run the migrations job first"));
        JwtKeyRing keys = JwtKeyRing.fromKeyset(keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, sealed));
        SecurityProperties.AccessToken token = properties.accessToken();
        return new AccessTokenService(
                keys,
                new AccessTokenService.Settings(token.issuer(), token.audience(), token.ttl(), token.clockSkew()),
                clock.getIfAvailable(Clock::systemUTC),
                random.getIfAvailable(SecureRandom::new));
    }

    /**
     * The refresh-token service.
     *
     * @param store the session store the service provides
     * @param properties the security configuration
     * @param clock the service's clock, if any
     * @param random the service's CSPRNG, if any
     * @param ids the service's id factory, if any
     * @return the service
     */
    @Bean
    public RefreshTokenService refreshTokenService(
            RefreshSessionStore store,
            SecurityProperties properties,
            ObjectProvider<Clock> clock,
            ObjectProvider<SecureRandom> random,
            ObjectProvider<KairosIdFactory> ids) {
        Clock time = clock.getIfAvailable(Clock::systemUTC);
        SecureRandom secureRandom = random.getIfAvailable(SecureRandom::new);
        return new RefreshTokenService(
                store,
                ids.getIfAvailable(() -> new KairosIdFactory(time, secureRandom)),
                time,
                secureRandom,
                properties.refreshToken().ttl());
    }
}
