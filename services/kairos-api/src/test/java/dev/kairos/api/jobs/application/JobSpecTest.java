package dev.kairos.api.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.MisfirePolicy;
import dev.kairos.domain.schedule.ScheduleSpec;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class JobSpecTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String VALID = """
            {"name":"nightly.sync","schedule":{"type":"INTERVAL","everySec":600},
             "endpoint":"orders-create","request":{"pathParams":{"order-id":"42"}}}
            """;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void defaultsAndExplicitNullBodyHaveDistinctCallSemantics() {
        JobSpec spec = parse(VALID);
        assertThat(spec.priority()).isEqualTo(5);
        assertThat(spec.misfirePolicy()).isEqualTo(MisfirePolicy.RUN_LATEST);
        assertThat(spec.autoPauseAfter()).isEqualTo(3);
        assertThat(spec.maxCatchup()).isEqualTo(100);
        assertThat(spec.retryPolicy()).isNull();
        assertThat(spec.request().body()).isNull();
        assertThat(JobSpec.request(json.readTree("{\"body\":null}")).body().isNull())
                .isTrue();
        spec.validateEndpoint("https://internal/orders/{order-id}", Map.of());
        assertThat(spec.schedule()).isInstanceOf(ScheduleSpec.Interval.class);
        assertThat(JobSchedule.from(spec, NOW).nextFireAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "name|\"bad/name\"",
                "name|\"\"",
                "endpoint|\"UPPER\"",
                "priority|0",
                "priority|11",
                "priority|1.5",
                "priority|\"7\"",
                "maxCatchup|-1",
                "maxCatchup|1001",
                "maxLatenessSec|-1",
                "maxLatenessSec|2147484",
                "autoPauseAfter|101",
                "autoPauseAfter|-1",
                "misfirePolicy|\"UNKNOWN\"",
                "misfirePolicy|false",
                "request|[]",
                "retryPolicy|[]"
            })
    void rejectsInvalidDefinitionValues(String field, String value) {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(VALID);
        node.set(field, json.readTree(value));
        assertThatThrownBy(() -> JobSpec.parse(node, NOW, json)).isInstanceOf(ValidationFailedException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"type\":\"UNKNOWN\"}",
                "{\"type\":\"CRON\",\"expression\":\"bad\",\"timezone\":\"UTC\"}",
                "{\"type\":\"CRON\",\"expression\":\"* * * * *\",\"timezone\":\"Invalid/Zone\"}",
                "{\"type\":\"INTERVAL\",\"everySec\":0}",
                "{\"type\":\"INTERVAL\",\"everySec\":1.1}",
                "{\"type\":\"ONE_TIME\",\"at\":\"2020-01-01T00:00:00Z\"}",
                "{\"type\":\"INTERVAL\",\"everySec\":60,\"spreadSec\":-1}"
            })
    void rejectsInvalidSchedules(String schedule) {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(VALID);
        node.set("schedule", json.readTree(schedule));
        assertThatThrownBy(() -> JobSpec.parse(node, NOW, json)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void rejectsNonObjectBodiesLargeSpecsAndSpreadAtTenPercentOfThePeriod() {
        assertThatThrownBy(() -> parse("[]")).isInstanceOf(ValidationFailedException.class);
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(VALID);
        node.put("extra", "x".repeat(JobSpec.MAX_SPEC_BYTES));
        assertThatThrownBy(() -> JobSpec.parse(node, NOW, json)).isInstanceOf(ValidationFailedException.class);
        JobSpec spec = parse(VALID.replace("\"everySec\":600", "\"everySec\":60,\"spreadSec\":6"));
        assertThatThrownBy(() -> JobSchedule.from(spec, NOW)).isInstanceOf(ValidationFailedException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"pathParams\":{}}",
                "{\"pathParams\":{\"order-id\":\"42\",\"extra\":\"x\"}}",
                "{\"pathParams\":{\"order-id\":42}}",
                "{\"pathParams\":{\"order-id\":\"42\"},\"headers\":{\"Authorization\":\"secret\"}}",
                "{\"pathParams\":{\"order-id\":\"42\"},\"headers\":{\"Content-Length\":\"1\"}}",
                "{\"pathParams\":{\"order-id\":\"42\"},\"headers\":{\"Bad Header\":\"x\"}}",
                "{\"pathParams\":{\"order-id\":\"42\"},\"headers\":{\"X-Test\":\"line\\r\\nnext\"}}",
                "{\"pathParams\":{\"order-id\":\"42\"},\"headers\":{\"X-Test\":\"a\",\"x-test\":\"b\"}}",
                "{\"pathParams\":{\"order-id\":\"42\"},\"query\":{\"\":\"x\"}}"
            })
    void rejectsInvalidEndpointRequestValues(String request) {
        assertThatThrownBy(() -> {
                    JobSpec.Request parsed = JobSpec.request(json.readTree(request));
                    JobSpec.validateEndpoint(parsed, "https://internal/orders/{order-id}", Map.of());
                })
                .isInstanceOf(ValidationFailedException.class);
    }

    private JobSpec parse(String value) {
        return JobSpec.parse(json.readTree(value), NOW, json);
    }
}
