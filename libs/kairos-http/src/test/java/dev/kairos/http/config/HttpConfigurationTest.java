package dev.kairos.http.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.http.auth.Authenticator;
import dev.kairos.http.auth.OAuth2TokenProvider;
import dev.kairos.http.client.HttpClientFactory;
import dev.kairos.http.dns.HostLookup;
import dev.kairos.http.policy.DenyReason;
import dev.kairos.http.policy.TargetPolicy;
import dev.kairos.http.redact.Redactor;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HttpConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(HttpConfiguration.class);

    @Test
    void wiresTheStackWithProductionDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HttpClientFactory.class);
            assertThat(context).hasSingleBean(Redactor.class);
            assertThat(context).hasSingleBean(OAuth2TokenProvider.class);
            assertThat(context).hasSingleBean(Authenticator.class);
            TargetPolicy policy = context.getBean(TargetPolicy.class);
            assertThat(policy.checkUrl(URI.create("https://orders.payments.svc.cluster.local:8443/x"))
                            .isAllowed())
                    .isTrue();
            assertThat(policy.checkUrl(URI.create("https://billing.internal/")).isAllowed())
                    .isTrue();
            assertThat(policy.checkUrl(URI.create("https://billing.internal:8080/"))
                            .isAllowed())
                    .isFalse();
            assertThat(policy.checkAddress(InetAddress.ofLiteral("10.4.5.6"))).isEmpty();
            assertThat(policy.checkAddress(InetAddress.ofLiteral("169.254.169.254")))
                    .contains(DenyReason.DENIED_ADDRESS);
            HttpProperties http = context.getBean(HttpProperties.class);
            assertThat(http.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(http.toSettings().maxConnectionsPerRoute()).isEqualTo(200);
            assertThat(context.getBean(Redactor.class).isSecretHeader("x-api-key"))
                    .isTrue();
        });
    }

    @Test
    void deniesInfrastructureAndTheKubernetesApi() {
        HostLookup lookup = host -> {
            if (host.equals("postgres")) {
                return List.of(InetAddress.ofLiteral("10.20.0.5"));
            }
            throw new UnknownHostException("unknown");
        };
        runner.withBean(HostLookup.class, () -> lookup)
                .withPropertyValues(
                        "kairos.target-policy.allowed-hosts=postgres,redis",
                        "kairos.target-policy.infrastructure-hosts=postgres,redis,10.30.0.7",
                        "kairos.target-policy.infrastructure-cidrs=10.40.0.0/16",
                        "KUBERNETES_SERVICE_HOST=10.96.0.1")
                .run(context -> {
                    TargetPolicy policy = context.getBean(TargetPolicy.class);
                    assertThat(policy.checkHostName("postgres")).contains(DenyReason.DENIED_HOST);
                    assertThat(policy.checkHostName("redis")).contains(DenyReason.DENIED_HOST);
                    assertThat(policy.checkAddress(InetAddress.ofLiteral("10.20.0.5")))
                            .contains(DenyReason.DENIED_ADDRESS);
                    assertThat(policy.checkAddress(InetAddress.ofLiteral("10.30.0.7")))
                            .contains(DenyReason.DENIED_ADDRESS);
                    assertThat(policy.checkAddress(InetAddress.ofLiteral("10.40.1.1")))
                            .contains(DenyReason.DENIED_ADDRESS);
                    assertThat(policy.checkAddress(InetAddress.ofLiteral("10.96.0.1")))
                            .contains(DenyReason.DENIED_ADDRESS);
                    assertThat(policy.checkAddress(InetAddress.ofLiteral("10.96.0.2")))
                            .isEmpty();
                });
    }

    @Test
    void configurationCannotAllowTheFixedDenyList() {
        runner.withPropertyValues(
                        "kairos.target-policy.allowed-cidrs=0.0.0.0/0,::/0",
                        "kairos.target-policy.allowed-hosts=localhost,metadata.google.internal")
                .run(context -> {
                    TargetPolicy policy = context.getBean(TargetPolicy.class);
                    assertThat(policy.checkAddress(InetAddress.ofLiteral("127.0.0.1")))
                            .contains(DenyReason.DENIED_ADDRESS);
                    assertThat(policy.checkAddress(InetAddress.ofLiteral("169.254.169.254")))
                            .contains(DenyReason.DENIED_ADDRESS);
                    assertThat(policy.checkHostName("metadata.google.internal")).contains(DenyReason.DENIED_HOST);
                });
    }

    @Test
    void rejectsInvalidSettings() {
        runner.withPropertyValues("kairos.target-policy.allowed-cidrs=10.0.0.1/8")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.target-policy.allowed-ports=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.http.max-connections-per-route=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
