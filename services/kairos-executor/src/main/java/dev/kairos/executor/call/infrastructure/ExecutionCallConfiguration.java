package dev.kairos.executor.call.infrastructure;

import dev.kairos.executor.call.application.ResultPublisher;
import dev.kairos.http.auth.Authenticator;
import dev.kairos.http.client.HttpClientFactory;
import dev.kairos.http.config.HttpConfiguration;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.KafkaPublisher;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.config.KeyManagementConfiguration;
import dev.kairos.security.config.PlatformKeysetConfiguration;
import dev.kairos.security.config.SecurityProperties;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.PlatformKeyPurpose;
import dev.kairos.security.keyset.PlatformKeysetStore;
import dev.kairos.security.keyset.SealedKeyset;
import dev.kairos.security.signing.RequestSigner;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.JwtKeyRing;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Explicit wiring for endpoint rendering, outgoing calls and durable result publishing. */
@Configuration(proxyBeanMethods = false)
@Import({HttpConfiguration.class, KeyManagementConfiguration.class, PlatformKeysetConfiguration.class})
public class ExecutionCallConfiguration {

    /** Verifies the 30-second, executor-audience token used only by kairos-api for endpoint tests. */
    @Bean("executorInternalAccessTokens")
    public AccessTokenService executorInternalAccessTokens(
            PlatformKeysetStore store,
            KeysetService keysets,
            SecurityProperties properties,
            ObjectProvider<Clock> clock,
            ObjectProvider<SecureRandom> random,
            @org.springframework.beans.factory.annotation.Value(
                            "${kairos.security.internal-token.audience:kairos-executor-internal}")
                    String audience) {
        SealedKeyset sealed = store.find(PlatformKeyPurpose.JWT_SIGNING)
                .orElseThrow(() -> new IllegalStateException("the platform JWT signing keyset is missing"));
        var token = properties.accessToken();
        return new AccessTokenService(
                JwtKeyRing.fromKeyset(keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, sealed)),
                new AccessTokenService.Settings(token.issuer(), audience, Duration.ofSeconds(30), token.clockSkew()),
                clock.getIfAvailable(Clock::systemUTC),
                random.getIfAvailable(SecureRandom::new));
    }

    /** Loads immutable endpoint versions from the control database. */
    @Bean
    public JdbcEndpointVersions executorEndpointVersions(
            @Qualifier("controlJdbcClient") JdbcClient control, JsonMapper json) {
        return new JdbcEndpointVersions(control, json);
    }

    /** Resolves secrets, CA bundles and team keysets for a single call. */
    @Bean
    public JdbcExecutionSecrets executorExecutionSecrets(
            @Qualifier("controlJdbcClient") JdbcClient control,
            JsonMapper json,
            KeysetService keysets,
            SecretCipher cipher) {
        return new JdbcExecutionSecrets(control, json, keysets, cipher);
    }

    /** Owns one Resilience4j breaker and rate limiter per endpoint. */
    @Bean
    public EndpointProtections executorEndpointProtections(MeterRegistry meters) {
        return new EndpointProtections(meters);
    }

    /** Builds signed, authenticated HTTP requests from dispatch snapshots. */
    @Bean
    public CallRenderer executorCallRenderer(
            JdbcExecutionSecrets secrets,
            HttpClientFactory clients,
            RequestSigner signer,
            JsonMapper json,
            Authenticator authenticator) {
        return new CallRenderer(secrets, clients, signer, json, authenticator);
    }

    /** Closes the shared acks=all Kafka producer with the Spring context. */
    @Bean(destroyMethod = "close")
    public KafkaPublisher executorResultKafkaPublisher(KafkaClientFactory clients) {
        return clients.publisher("executor-result");
    }

    /** Publishes to kairos.run.result and waits for broker acknowledgement. */
    @Bean
    public ResultPublisher executorResultPublisher(KafkaPublisher executorResultKafkaPublisher) {
        return new KafkaResultPublisher(executorResultKafkaPublisher);
    }
}
