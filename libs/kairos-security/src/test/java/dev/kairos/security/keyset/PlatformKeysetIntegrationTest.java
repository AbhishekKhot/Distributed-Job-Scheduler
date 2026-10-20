package dev.kairos.security.keyset;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.security.SecurityFixtures;
import dev.kairos.security.config.SecurityProperties;
import dev.kairos.security.config.TokenConfiguration;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.InMemoryRefreshSessionStore;
import dev.kairos.security.token.RefreshSessionStore;
import dev.kairos.security.token.RefreshTokenService;
import dev.kairos.security.token.TokenVerification;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.RepositoryFiles;
import dev.kairos.testsupport.TestIds;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** The platform keyset store, bootstrap and token wiring against the real control schema. */
@KairosIntegrationTest(kafka = false, redis = false)
class PlatformKeysetIntegrationTest {

    private static final String CONTROL_SCHEMA =
            "jobs/kairos-migrations/src/main/resources/db/control/V2026_10_20_0900__create_control_schema.sql";

    private static JdbcClient control;

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
    private final String localKey = SecurityFixtures.randomHexKey();
    private final KeysetService keysets =
            SecurityFixtures.keysetService(LocalKeyEncryptionKey.forLocalProfile(localKey, true));

    @BeforeAll
    static void applySchema(KairosPostgres postgres) {
        new ResourceDatabasePopulator(new FileSystemResource(RepositoryFiles.resolve(CONTROL_SCHEMA)))
                .execute(postgres.controlOwnerDataSource());
        control = JdbcClient.create(postgres.controlDataSource());
    }

    @BeforeEach
    void clean() {
        control.sql("DELETE FROM scheduler.platform_keysets").update();
    }

    @Test
    void storesAndReadsASealedKeyset() {
        JdbcPlatformKeysetStore store = new JdbcPlatformKeysetStore(control);
        SealedKeyset sealed = keysets.createJwtSigningKeyset();

        assertThat(store.find(PlatformKeyPurpose.JWT_SIGNING)).isEmpty();
        assertThat(store.insertIfAbsent(PlatformKeyPurpose.JWT_SIGNING, sealed, clock.instant()))
                .isTrue();
        assertThat(store.insertIfAbsent(
                        PlatformKeyPurpose.JWT_SIGNING, keysets.createJwtSigningKeyset(), clock.instant()))
                .isFalse();
        assertThat(store.find(PlatformKeyPurpose.JWT_SIGNING)).contains(sealed);
    }

    @Test
    void concurrentBootstrapsCreateExactlyOneKeyset() throws Exception {
        JdbcPlatformKeysetStore store = new JdbcPlatformKeysetStore(control);
        List<Callable<Boolean>> runs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            runs.add(() -> new PlatformKeysetBootstrap(store, keysets, clock).ensureJwtSigningKeyset());
        }

        int created = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Boolean> result : pool.invokeAll(runs)) {
                created += result.get() ? 1 : 0;
            }
        }

        assertThat(created).isEqualTo(1);
        assertThat(control.sql("SELECT count(*) FROM scheduler.platform_keysets")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(new PlatformKeysetBootstrap(store, keysets, clock).ensureJwtSigningKeyset())
                .isFalse();
    }

    @Test
    void theApiWiringIssuesTokensFromTheStoredKeyset() {
        new PlatformKeysetBootstrap(new JdbcPlatformKeysetStore(control), keysets, clock).ensureJwtSigningKeyset();

        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withUserConfiguration(TokenConfiguration.class)
                .withBean("controlJdbcClient", JdbcClient.class, () -> control)
                .withBean(RefreshSessionStore.class, InMemoryRefreshSessionStore::new)
                .withBean(Clock.class, () -> clock)
                .withPropertyValues("kairos.security.kek.local-key=" + localKey)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AccessTokenService tokens = context.getBean(AccessTokenService.class);
                    String token = tokens.issue(TestIds.userId(), TestIds.teamId(), UUID.randomUUID())
                            .token()
                            .reveal();
                    assertThat(tokens.verify(token)).isInstanceOf(TokenVerification.Valid.class);
                    assertThat(context.getBean(RefreshTokenService.class)
                                    .issue(TestIds.userId(), null, null)
                                    .session()
                                    .createdAt())
                            .isEqualTo(clock.instant());
                    assertThat(context.getBean(SecurityProperties.class).toString())
                            .doesNotContain(localKey);
                });
    }

    @Test
    void theApiRefusesToStartWithoutTheKeyset() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withUserConfiguration(TokenConfiguration.class)
                .withBean("controlJdbcClient", JdbcClient.class, () -> control)
                .withBean(RefreshSessionStore.class, InMemoryRefreshSessionStore::new)
                .withPropertyValues("kairos.security.kek.local-key=" + localKey)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("run the migrations job first"));
    }
}
