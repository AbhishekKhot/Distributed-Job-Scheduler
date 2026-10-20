package dev.kairos.security.config;

import dev.kairos.security.keyset.JdbcPlatformKeysetStore;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.PlatformKeysetBootstrap;
import dev.kairos.security.keyset.PlatformKeysetStore;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The platform keyset store and its bootstrap. Needs the control database's {@code controlJdbcClient} bean (from the
 * persistence library). The migrations job calls {@link PlatformKeysetBootstrap#ensureJwtSigningKeyset()} after the
 * schema is migrated.
 */
@Configuration(proxyBeanMethods = false)
@Import(KeyManagementConfiguration.class)
public class PlatformKeysetConfiguration {

    /** Creates the configuration; Spring calls it. */
    public PlatformKeysetConfiguration() {}

    /**
     * The store on {@code platform_keysets}.
     *
     * @param control the control database's client
     * @return the store
     */
    @Bean
    public PlatformKeysetStore platformKeysetStore(@Qualifier("controlJdbcClient") JdbcClient control) {
        return new JdbcPlatformKeysetStore(control);
    }

    /**
     * The bootstrap that creates missing platform keysets.
     *
     * @param store the store
     * @param keysets the keyset service
     * @param clock the service's clock, if any
     * @return the bootstrap
     */
    @Bean
    public PlatformKeysetBootstrap platformKeysetBootstrap(
            PlatformKeysetStore store, KeysetService keysets, ObjectProvider<Clock> clock) {
        return new PlatformKeysetBootstrap(store, keysets, clock.getIfAvailable(Clock::systemUTC));
    }
}
