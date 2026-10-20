package dev.kairos.http.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.testsupport.TestIds;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class AuthMaterialTest {

    private final TokenCacheKey key = new TokenCacheKey(TestIds.teamId(), TestIds.endpointId(), 1, 1);

    @Test
    void secretsNeverAppearInToString() {
        AuthMaterial[] materials = {
            new AuthMaterial.Basic("svc", "pa55word"),
            new AuthMaterial.Bearer("bearer-secret"),
            new AuthMaterial.ApiKey(ApiKeyLocation.HEADER, "X-Api-Key", "key-secret"),
            new AuthMaterial.OAuth2ClientCredentials(
                    URI.create("https://token.internal/oauth/token"), "a b", "client", "client-secret", key)
        };

        for (AuthMaterial material : materials) {
            assertThat(material.toString())
                    .doesNotContain("pa55word")
                    .doesNotContain("bearer-secret")
                    .doesNotContain("key-secret")
                    .doesNotContain("client-secret");
            assertThat(material.secrets()).hasSize(1);
        }
        assertThat(new AuthMaterial.None().secrets()).isEmpty();
        assertThat(new AuthMaterial.None().type()).isEqualTo(AuthType.NONE);
    }

    @Test
    void typesMatchTheVariant() {
        assertThat(new AuthMaterial.Basic("u", "p").type()).isEqualTo(AuthType.BASIC);
        assertThat(new AuthMaterial.Bearer("t").type()).isEqualTo(AuthType.BEARER);
        assertThat(new AuthMaterial.ApiKey(ApiKeyLocation.QUERY, "k", "v").type())
                .isEqualTo(AuthType.API_KEY);
        assertThat(new AuthMaterial.OAuth2ClientCredentials(URI.create("https://t.internal/"), null, "c", "s", key)
                        .type())
                .isEqualTo(AuthType.OAUTH2_CLIENT_CREDENTIALS);
    }

    @Test
    void rejectsHeaderInjectionInSecrets() {
        assertThatThrownBy(() -> new AuthMaterial.Bearer("tok\r\nX-Injected: 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.Bearer("has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.Bearer("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.Bearer("t".repeat(AuthMaterial.MAX_SECRET + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.Basic("u", "p\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.Basic("u:x", "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.ApiKey(ApiKeyLocation.HEADER, "X-Key\r\n", "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.ApiKey(ApiKeyLocation.HEADER, "Host", "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.ApiKey(ApiKeyLocation.HEADER, "k".repeat(129), "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthMaterial.OAuth2ClientCredentials(
                        URI.create("https://t.internal/"), "a\nb", "c", "s", key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new AuthMaterial.OAuth2ClientCredentials(URI.create("https://t.internal/"), "", "c", "s", key))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesTheCacheKey() {
        assertThatThrownBy(() -> new TokenCacheKey(TestIds.teamId(), TestIds.endpointId(), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenCacheKey(TestIds.teamId(), TestIds.endpointId(), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliersRefuseMaterialOfAnotherType() {
        for (AuthApplier applier : StaticAuthAppliers.all()) {
            AuthMaterial other =
                    applier.type() == AuthType.NONE ? new AuthMaterial.Bearer("t") : new AuthMaterial.None();
            assertThatThrownBy(() -> applier.apply(null, other, null)).isInstanceOf(IllegalArgumentException.class);
        }
        OAuth2ClientCredentialsApplier oauth =
                new OAuth2ClientCredentialsApplier(new OAuth2TokenProvider(Clock.systemUTC(), 1));
        assertThat(oauth.type()).isEqualTo(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        assertThatThrownBy(() -> oauth.apply(null, new AuthMaterial.None(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OAuth2TokenProvider(Clock.systemUTC(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authFailuresCarryTheClassifierInput() {
        AuthFailedException failure =
                new AuthFailedException(new dev.kairos.domain.retry.CallResult.DnsFailed(), "the token request failed");

        assertThat(failure.callResult()).isEqualTo(new dev.kairos.domain.retry.CallResult.DnsFailed());
        assertThat(failure).hasMessage("the token request failed");
    }
}
