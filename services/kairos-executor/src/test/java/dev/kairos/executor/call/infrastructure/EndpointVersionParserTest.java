package dev.kairos.executor.call.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.retry.StatusOverride;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.http.auth.ApiKeyLocation;
import dev.kairos.http.auth.AuthType;
import dev.kairos.http.request.HttpMethod;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Parses endpoint versions from the immutable database representation. */
class EndpointVersionParserTest {

    private final EndpointVersionParser parser = new EndpointVersionParser();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void parsesAuthLimitsStatusesOutputsAndHeaders() throws Exception {
        EndpointVersion endpoint = parser.parse(json.readTree("""
                {
                  "urlTemplate":"https://target.example.test/orders/{id}",
                  "method":"POST",
                  "timeoutMs":15000,
                  "defaultHeaders":{"X-Tenant":"acme"},
                  "auth":{"type":"API_KEY","secretRef":"target-key","location":"HEADER","name":"X-Api-Key"},
                  "caBundle":"corp-ca",
                  "successStatus":["2xx","304"],
                  "statusOverrides":{"409":"SUCCESS"},
                  "outputs":{"orderId":{"from":"BODY","path":"$.id","required":true},"status":{"from":"STATUS","sensitive":true}},
                  "limits":{"ratePerSec":7}
                }
                """));

        assertThat(endpoint.urlTemplate()).endsWith("/orders/{id}");
        assertThat(endpoint.method()).isEqualTo(HttpMethod.POST);
        assertThat(endpoint.timeout().toMillis()).isEqualTo(15_000);
        assertThat(endpoint.defaultHeaders()).containsEntry("X-Tenant", "acme");
        assertThat(endpoint.authType()).isEqualTo(AuthType.API_KEY);
        assertThat(endpoint.apiKeyLocation()).isEqualTo(ApiKeyLocation.HEADER);
        assertThat(endpoint.secretRef()).isEqualTo("target-key");
        assertThat(endpoint.statusRules().statusOverrides()).containsEntry(409, StatusOverride.SUCCESS);
        assertThat(endpoint.outputs()).containsKeys("orderId", "status");
        assertThat(endpoint.outputs().get("status").from()).isEqualTo(OutputSpec.Source.STATUS);
        assertThat(endpoint.outputs().get("status").sensitive()).isTrue();
        assertThat(endpoint.ratePerSec()).isEqualTo(7);
        assertThat(endpoint.caBundle()).isEqualTo("corp-ca");
    }

    @Test
    void defaultsOptionalValuesAndRejectsMalformedSpecs() throws Exception {
        EndpointVersion endpoint = parser.parse(json.readTree("""
                {"urlTemplate":"https://target.example.test/","method":"GET","auth":{"type":"NONE"}}
                """));

        assertThat(endpoint.timeout().toMillis()).isEqualTo(30_000);
        assertThat(endpoint.statusRules().successStatus()).hasSize(1);
        assertThat(endpoint.ratePerSec()).isZero();
        assertThat(endpoint.defaultHeaders()).isEmpty();
        assertThatThrownBy(() -> parser.parse(json.readTree("{\"method\":\"GET\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(json.readTree("""
                {"urlTemplate":"https://target.example.test/","method":"GET","auth":{"type":"NONE"},
                 "defaultHeaders":{"X-Bad":42}}
                """))).isInstanceOf(IllegalArgumentException.class);
    }
}
