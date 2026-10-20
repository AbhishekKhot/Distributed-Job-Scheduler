package dev.kairos.http.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The platform target policy, bound from {@code kairos.target-policy.*} (HLD section 11).
 *
 * <p>These settings can only allow more internal targets or deny more infrastructure; the fixed deny list of
 * {@link dev.kairos.http.policy.TargetPolicy} (loopback, link-local and metadata, multicast, the Kubernetes API) always
 * applies on top and has no setting. The Kubernetes API service address is also taken from the
 * {@code KUBERNETES_SERVICE_HOST} environment variable that Kubernetes sets in every pod.
 *
 * @param allowedSuffixes DNS suffixes of internal targets
 * @param allowedHosts exact internal host names
 * @param allowedCidrs internal networks every resolved address must be in
 * @param allowedPorts target ports
 * @param infrastructureHosts infrastructure host names (databases, Kafka, Redis) denied by name and, resolved at
 *     startup, by address
 * @param infrastructureCidrs infrastructure networks or addresses denied
 */
@Validated
@ConfigurationProperties("kairos.target-policy")
public record TargetPolicyProperties(
        @NotNull @Size(max = 64) @DefaultValue({".svc.cluster.local", ".internal"})
        List<String> allowedSuffixes,

        @NotNull @Size(max = 256) @DefaultValue List<String> allowedHosts,

        @NotNull @Size(max = 64) @DefaultValue({"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
        List<String> allowedCidrs,

        @NotEmpty @Size(max = 64) @DefaultValue({"443", "8443"})
        List<@NotNull @Min(1) @Max(65_535) Integer> allowedPorts,

        @NotNull @Size(max = 64) @DefaultValue List<String> infrastructureHosts,

        @NotNull @Size(max = 64) @DefaultValue List<String> infrastructureCidrs) {}
