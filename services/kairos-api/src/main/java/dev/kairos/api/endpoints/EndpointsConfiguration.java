package dev.kairos.api.endpoints;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.cabundles.application.CaBundleLookup;
import dev.kairos.api.endpoints.application.EndpointHealthReader;
import dev.kairos.api.endpoints.application.EndpointStore;
import dev.kairos.api.endpoints.application.EndpointTestGateway;
import dev.kairos.api.endpoints.application.EndpointsUseCase;
import dev.kairos.api.endpoints.infrastructure.EndpointTestTokenIssuer;
import dev.kairos.api.endpoints.infrastructure.ExecutorEndpointTestGateway;
import dev.kairos.api.endpoints.infrastructure.JdbcEndpointHealthReader;
import dev.kairos.api.endpoints.infrastructure.JdbcEndpointStore;
import dev.kairos.api.endpoints.infrastructure.ScheduleStatusReconciler;
import dev.kairos.api.secrets.application.SecretLookup;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.http.policy.TargetPolicy;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.config.SecurityProperties;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.PlatformKeyPurpose;
import dev.kairos.security.keyset.PlatformKeysetStore;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.JwtKeyRing;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Wires endpoint management. */
@Configuration(proxyBeanMethods = false)
public class EndpointsConfiguration {
    @Bean
    public EndpointStore endpointStore(
            @Qualifier("controlJdbcClient") JdbcClient control,
            ControlTransactions transactions,
            AuditWriter audit,
            SecretLookup secrets,
            CaBundleLookup caBundles,
            JsonMapper json,
            CellRouter cells) {
        return new JdbcEndpointStore(control, transactions, audit, secrets, caBundles, json, cells);
    }

    @Bean
    public EndpointsUseCase endpointsUseCase(
            EndpointStore store,
            TargetPolicy targets,
            KairosIdFactory ids,
            Clock clock,
            ScheduleStatusReconciler schedules,
            EndpointTestGateway testGateway,
            RateLimiter rateLimiter,
            EndpointHealthReader healthReader) {
        return new EndpointsUseCase(store, targets, ids, clock, schedules, testGateway, rateLimiter, healthReader);
    }

    @Bean
    public ScheduleStatusReconciler endpointScheduleStatusReconciler(CellRouter cells) {
        return new ScheduleStatusReconciler(cells);
    }

    @Bean
    public EndpointHealthReader endpointHealthReader(CellRouter cells, Clock clock) {
        return new JdbcEndpointHealthReader(cells, clock);
    }

    @Bean
    public EndpointTestTokenIssuer endpointTestTokenIssuer(
            PlatformKeysetStore store,
            KeysetService keysets,
            SecurityProperties properties,
            Clock clock,
            SecureRandom random,
            KairosIdFactory ids,
            @org.springframework.beans.factory.annotation.Value(
                            "${kairos.security.internal-token.audience:kairos-executor-internal}")
                    String audience) {
        var sealed = store.find(PlatformKeyPurpose.JWT_SIGNING)
                .orElseThrow(() -> new IllegalStateException("the platform JWT signing keyset is missing"));
        var token = properties.accessToken();
        AccessTokenService service = new AccessTokenService(
                JwtKeyRing.fromKeyset(keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, sealed)),
                new AccessTokenService.Settings(token.issuer(), audience, Duration.ofSeconds(30), token.clockSkew()),
                clock,
                random);
        return new EndpointTestTokenIssuer(service, ids);
    }

    @Bean
    public EndpointTestGateway endpointTestGateway(
            @org.springframework.beans.factory.annotation.Value(
                            "${kairos.api.executor.internal-base-url:http://kairos-executor:8080}")
                    String baseUrl,
            EndpointTestTokenIssuer tokens) {
        return new ExecutorEndpointTestGateway(tokens, baseUrl);
    }
}
