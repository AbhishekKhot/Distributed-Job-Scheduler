package dev.kairos.state.workflow.infrastructure;

import com.google.protobuf.Value;
import dev.kairos.domain.id.RunId;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.proto.v1.Result;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.SealedKeyset;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies the executor's encrypted output envelope and stores it without exposing sensitive plaintext. */
public final class WorkflowOutputs {
    private final JdbcClient control;
    private final JsonMapper json;
    private final KeysetService keysets;
    private final SecretCipher cipher;

    public WorkflowOutputs(JdbcClient control, JsonMapper json, KeysetService keysets, SecretCipher cipher) {
        this.control = control;
        this.json = json;
        this.keysets = keysets;
        this.cipher = cipher;
    }

    public void store(Cell cell, InflightRun run, Result result, Instant at) {
        JsonNode declarations = json.readTree(control.sql("""
                SELECT v.spec::text FROM scheduler.api_endpoint_versions v JOIN scheduler.api_endpoints e
                ON e.endpoint_id=v.endpoint_id WHERE e.team_id=:team AND v.endpoint_id=:id AND v.version=:version
                """)
                        .param("team", run.teamId().value())
                        .param("id", run.endpointId().value())
                        .param("version", run.endpointVersion())
                        .query(String.class)
                        .single())
                .path("outputs");
        Map<String, Object> plain = new LinkedHashMap<>();
        result.getOutputsMap().forEach((name, value) -> {
            if (!declarations.has(name)
                    || declarations.path(name).path("sensitive").asBoolean()) invalid();
            plain.put(name, value(value));
        });
        byte[] sealed = result.getSealedOutputs().toByteArray();
        JsonNode encrypted = sealed.length == 0 ? json.createObjectNode() : json.readTree(sealed);
        if (sealed.length > 0
                && (!encrypted.isObject()
                        || encrypted.path("v").asInt() != 1
                        || !encrypted.path("values").isObject())) invalid();
        if (sealed.length > 0
                && encrypted.properties().stream()
                        .anyMatch(entry ->
                                !entry.getKey().equals("v") && !entry.getKey().equals("values"))) invalid();
        var values = encrypted.path("values");
        if (!values.isMissingNode() && values.size() > 256) invalid();
        if (!values.isEmpty() && !values.isMissingNode()) {
            SealedKeyset stored = control.sql(
                            "SELECT encrypted_keyset,kek_uri FROM scheduler.team_keysets WHERE team_id=:team AND purpose='OUTPUTS'")
                    .param("team", run.teamId().value())
                    .query((rs, n) -> new SealedKeyset(rs.getBytes(1), rs.getString(2)))
                    .single();
            var keys = keysets.aead(run.teamId(), KeyPurpose.OUTPUTS, stored);
            for (var entry : values.properties()) {
                String name = entry.getKey();
                if (!declarations.has(name)
                        || !declarations.path(name).path("sensitive").asBoolean()
                        || plain.containsKey(name)
                        || !entry.getValue().isString()) invalid();
                byte[] plaintext = cipher.decryptOutput(
                        keys,
                        new RunId(run.targetId()),
                        name,
                        Base64.getDecoder().decode(entry.getValue().asString()));
                try {
                    if (json.readTree(plaintext).isMissingNode()) invalid();
                } finally {
                    Arrays.fill(plaintext, (byte) 0);
                }
            }
        }
        for (var entry : declarations.properties())
            if (entry.getValue().path("required").asBoolean()
                    && !plain.containsKey(entry.getKey())
                    && !values.has(entry.getKey())) invalid();
        String publicJson = json.writeValueAsString(plain);
        if (publicJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + sealed.length > 24576) invalid();
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.task_outputs(scheduled_at,workflow_run_id,task_key,team_id,outputs,sealed,created_at)
                VALUES (:scheduled,:run,:task,:team,CAST(:outputs AS jsonb),:sealed,:at)
                """)
                .param("scheduled", run.scheduledAt().atOffset(ZoneOffset.UTC))
                .param("run", run.targetId())
                .param("task", run.taskKeyColumn())
                .param("team", run.teamId().value())
                .param("outputs", publicJson)
                .param("sealed", sealed.length == 0 ? null : sealed, Types.BINARY)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static Object value(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE -> null;
            case BOOL_VALUE -> value.getBoolValue();
            case NUMBER_VALUE -> {
                double number = value.getNumberValue();
                if (!Double.isFinite(number)) invalid();
                yield number;
            }
            case STRING_VALUE -> value.getStringValue();
            case STRUCT_VALUE -> {
                Map<String, Object> map = new LinkedHashMap<>();
                value.getStructValue().getFieldsMap().forEach((k, v) -> map.put(k, value(v)));
                yield map;
            }
            case LIST_VALUE ->
                value.getListValue().getValuesList().stream()
                        .map(WorkflowOutputs::value)
                        .toList();
            case KIND_NOT_SET -> throw new IllegalArgumentException("Invalid workflow output");
        };
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid workflow output envelope");
    }
}
