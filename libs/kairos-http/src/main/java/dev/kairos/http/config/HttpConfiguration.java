package dev.kairos.http.config;

import dev.kairos.http.auth.Authenticator;
import dev.kairos.http.auth.OAuth2TokenProvider;
import dev.kairos.http.client.HttpClientFactory;
import dev.kairos.http.dns.HostLookup;
import dev.kairos.http.policy.Cidr;
import dev.kairos.http.policy.IpLiterals;
import dev.kairos.http.policy.TargetPolicy;
import dev.kairos.http.redact.Redactor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the outbound HTTP stack: the target policy, the client factory, redaction and target authentication. Imported
 * by the executor (calls) and the API (URL validation at endpoint registration).
 *
 * <p>Infrastructure host names are denied by name and, resolved once at startup, by address; a name that does not
 * resolve at startup stays denied by name. The Kubernetes API service address comes from
 * {@value #KUBERNETES_SERVICE_HOST}. A service may provide its own {@link Clock} or {@link HostLookup} bean; there is
 * no setting that relaxes the fixed deny list.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TargetPolicyProperties.class, HttpProperties.class})
public class HttpConfiguration {

    /** The environment variable Kubernetes sets to the API service address in every pod. */
    public static final String KUBERNETES_SERVICE_HOST = "KUBERNETES_SERVICE_HOST";

    /** Creates the configuration; Spring calls it. */
    public HttpConfiguration() {}

    /**
     * The platform target policy.
     *
     * @param properties the policy settings
     * @param environment the environment, for {@value #KUBERNETES_SERVICE_HOST}
     * @param lookup the service's host lookup, if any
     * @return the policy
     * @throws IllegalArgumentException if a suffix, host or CIDR is invalid
     */
    @Bean
    public TargetPolicy targetPolicy(
            TargetPolicyProperties properties, Environment environment, ObjectProvider<HostLookup> lookup) {
        HostLookup resolver = lookup.getIfAvailable(HostLookup::system);
        TargetPolicy.Builder builder = TargetPolicy.builder();
        properties.allowedSuffixes().forEach(builder::allowSuffix);
        properties.allowedHosts().forEach(builder::allowHost);
        properties.allowedCidrs().forEach(cidr -> builder.allowCidr(Cidr.parse(cidr)));
        properties.allowedPorts().forEach(builder::allowPort);
        properties.infrastructureCidrs().forEach(cidr -> builder.denyCidr(Cidr.parse(cidr)));
        properties.infrastructureHosts().forEach(host -> denyInfrastructure(builder, host, resolver));
        String kubernetes = environment.getProperty(KUBERNETES_SERVICE_HOST);
        if (kubernetes != null && !kubernetes.isBlank()) {
            denyInfrastructure(builder, kubernetes.strip(), resolver);
        }
        return builder.build();
    }

    /**
     * The client factory; closed with the context.
     *
     * @param policy the target policy
     * @param properties the client settings
     * @param lookup the service's host lookup, if any
     * @param clock the service's clock, if any
     * @return the factory
     */
    @Bean
    public HttpClientFactory httpClientFactory(
            TargetPolicy policy,
            HttpProperties properties,
            ObjectProvider<HostLookup> lookup,
            ObjectProvider<Clock> clock) {
        return new HttpClientFactory(
                policy,
                lookup.getIfAvailable(HostLookup::system),
                properties.toSettings(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * The redactor.
     *
     * @param properties the client settings, for the extra secret headers
     * @return the redactor
     */
    @Bean
    public Redactor redactor(HttpProperties properties) {
        return new Redactor(properties.secretHeaders());
    }

    /**
     * The OAuth 2.0 token cache.
     *
     * @param properties the client settings
     * @param clock the service's clock, if any
     * @return the provider
     */
    @Bean
    public OAuth2TokenProvider oauth2TokenProvider(HttpProperties properties, ObjectProvider<Clock> clock) {
        return new OAuth2TokenProvider(clock.getIfAvailable(Clock::systemUTC), properties.maxCachedTokens());
    }

    /**
     * The target authenticator.
     *
     * @param tokens the OAuth 2.0 token cache
     * @return the authenticator
     */
    @Bean
    public Authenticator authenticator(OAuth2TokenProvider tokens) {
        return new Authenticator(tokens);
    }

    private static void denyInfrastructure(TargetPolicy.Builder builder, String host, HostLookup lookup) {
        builder.denyInfrastructure(host);
        if (IpLiterals.parseAddress(host).isPresent()) {
            return;
        }
        try {
            for (InetAddress address : lookup.lookup(host)) {
                builder.denyCidr(Cidr.host(address));
            }
        } catch (UnknownHostException e) {
            // Denied by name; the address cannot be denied until the name resolves, which a restart picks up.
        }
    }
}
