package dev.kairos.mocktarget.config;

import dev.kairos.mocktarget.behavior.application.CallTracker;
import dev.kairos.mocktarget.behavior.application.VerifyRequest;
import dev.kairos.mocktarget.behavior.infrastructure.InMemoryCallTracker;
import dev.kairos.mocktarget.behavior.infrastructure.RequestVerification;
import dev.kairos.mocktarget.behavior.infrastructure.SnapshotWriter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundleRegistry;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Tool-local CA, bounded tracking and scheduled snapshots; no production TLS policy is relaxed. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MockTargetProperties.class)
@EnableScheduling
public class MockTargetConfiguration {
    @Bean
    public Clock mockClock() {
        return Clock.systemUTC();
    }

    @Bean
    public DevelopmentTls developmentTls(Clock clock) {
        return new DevelopmentTls(clock);
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> mockTls(
            DevelopmentTls tls, SslBundleRegistry registry) {
        registry.registerBundle("mock-target", tls.bundle());
        return factory -> {
            var ssl = new Ssl();
            ssl.setEnabled(true);
            ssl.setBundle("mock-target");
            ssl.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            factory.setSsl(ssl);
        };
    }

    @Bean
    public CallTracker callTracker(MockTargetProperties properties, Clock clock) {
        return new InMemoryCallTracker(properties.maxKeys(), clock);
    }

    @Bean
    public VerifyRequest requestVerification(MockTargetProperties properties, Clock clock) {
        var verifier = new RequestVerification(properties.verificationKeys(), clock);
        return verifier::verify;
    }

    @Bean(destroyMethod = "write")
    public SnapshotWriter snapshotWriter(CallTracker tracker, MockTargetProperties properties) {
        return new SnapshotWriter(tracker, properties.snapshotPath());
    }
}
