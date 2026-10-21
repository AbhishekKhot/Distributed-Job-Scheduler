package dev.kairos.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.migrations.schema.MigrationRunner;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.PlatformKeyPurpose;
import dev.kairos.security.keyset.PlatformKeysetBootstrap;
import dev.kairos.security.keyset.PlatformKeysetStore;
import dev.kairos.security.keyset.SealedKeyset;
import dev.kairos.security.token.JwtKeyRing;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The job creates the platform access-token signing keyset after migrating, and later runs leave it alone.
 *
 * <p>The application context runs the job once at startup, as in production, so the keyset exists before the first
 * test. The tests then run the job again and compare the stored row.
 */
@KairosIntegrationTest(postgres = true, kafka = false, redis = false)
@SpringBootTest(properties = "management.server.port=0")
@ActiveProfiles("local")
// The same fixed clock as the schema test: both migrate the same databases, so they must create the same partitions.
@Import(MigrationsIntegrationTest.FixedClock.class)
class JwtKeysetBootstrapIntegrationTest {

    /** Everything stored for the keyset, so any change to it shows up as a different string. */
    private static final String KEYSET_ROW = """
            SELECT encode(encrypted_keyset, 'hex') || '|' || kek_uri || '|' || rotated_at::text
              FROM scheduler.platform_keysets
             WHERE purpose = :purpose
            """;

    @Autowired
    private MigrationRunner runner;

    @Autowired
    private PlatformKeysetBootstrap bootstrap;

    @Autowired
    private PlatformKeysetStore store;

    @Autowired
    private KeysetService keysets;

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        KairosPostgres postgres = KairosPostgres.get();
        registry.add("kairos.db.username", postgres::ownerUsername);
        registry.add("kairos.db.password", postgres::ownerPassword);
        registry.add("kairos.control.db.url", postgres::controlJdbcUrl);
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            int number = cell;
            registry.add("kairos.cell[" + cell + "].db.url", () -> postgres.cellJdbcUrl(number));
        }
        registry.add("kairos.security.kek.local-key", TestKek::localKey);
    }

    @Test
    void theJobLeavesAnAccessTokenSigningKeysetTheApiCanOpen() {
        SealedKeyset sealed = store.find(PlatformKeyPurpose.JWT_SIGNING).orElseThrow();

        assertThat(sealed.kekUri()).isEqualTo(keysets.kekUri());
        JwtKeyRing keys = JwtKeyRing.fromKeyset(keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, sealed));
        assertThat(keys.jwks().get("keys"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(1);
    }

    @Test
    void runningTheJobAgainNeitherRotatesNorDuplicatesTheKeyset() {
        String before = keysetRow();

        runner.run(new DefaultApplicationArguments());

        assertThat(bootstrap.ensureJwtSigningKeyset()).isFalse();
        assertThat(keysetRow()).isEqualTo(before);
        assertThat(control()
                        .sql("SELECT count(*) FROM scheduler.platform_keysets")
                        .query(Long.class)
                        .single())
                .isOne();
    }

    private static String keysetRow() {
        List<String> rows = control()
                .sql(KEYSET_ROW)
                .param("purpose", PlatformKeyPurpose.JWT_SIGNING.name())
                .query(String.class)
                .list();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static JdbcClient control() {
        return JdbcClient.create(KairosPostgres.get().controlOwnerDataSource());
    }
}
