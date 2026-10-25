package dev.kairos.executor.call.infrastructure;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.template.JsonValue;
import dev.kairos.domain.template.OutputValue;
import dev.kairos.http.auth.AuthMaterial;
import dev.kairos.http.auth.TokenCacheKey;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.SealedKeyset;
import dev.kairos.security.keyset.TeamAead;
import dev.kairos.security.keyset.TeamSigningKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads just-in-time credentials, trust bundles and team keysets from the control database. */
public final class JdbcExecutionSecrets {

    private final JdbcClient control;
    private final JsonMapper json;
    private final KeysetService keysets;
    private final SecretCipher cipher;
    private final dev.kairos.persistence.cell.CellRouter cells;

    /** Creates the credential adapter. */
    public JdbcExecutionSecrets(
            JdbcClient control,
            JsonMapper json,
            KeysetService keysets,
            SecretCipher cipher,
            dev.kairos.persistence.cell.CellRouter cells) {
        this.control = Objects.requireNonNull(control, "control");
        this.json = Objects.requireNonNull(json, "json");
        this.keysets = Objects.requireNonNull(keysets, "keysets");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.cells = Objects.requireNonNull(cells, "cells");
    }

    /** Loads current secret fields and creates the HTTP authentication material for this one call. */
    public AuthMaterial auth(TeamId team, EndpointId endpointId, int endpointVersion, EndpointVersion endpoint) {
        if (endpoint.authType() == dev.kairos.http.auth.AuthType.NONE) return new AuthMaterial.None();
        String ref = endpoint.secretRef();
        if (ref == null) throw new IllegalStateException("The pinned endpoint credential reference is missing");
        Secret secret = secret(team, ref);
        Map<String, String> fields = decryptSecret(team, ref, secret);
        String expectedKind = switch (endpoint.authType()) {
            case NONE -> "NONE";
            case BASIC -> "BASIC";
            case BEARER -> "BEARER";
            case API_KEY -> "API_KEY";
            case OAUTH2_CLIENT_CREDENTIALS -> "OAUTH2_CLIENT";
        };
        if (!expectedKind.equals(secret.kind())) {
            throw new IllegalStateException("The endpoint credential is invalid");
        }
        return switch (endpoint.authType()) {
            case NONE -> new AuthMaterial.None();
            case BASIC -> new AuthMaterial.Basic(required(fields, "username"), required(fields, "password"));
            case BEARER -> new AuthMaterial.Bearer(required(fields, "token"));
            case API_KEY ->
                new AuthMaterial.ApiKey(
                        Objects.requireNonNull(endpoint.apiKeyLocation()),
                        Objects.requireNonNull(endpoint.apiKeyName()),
                        required(fields, "key"));
            case OAUTH2_CLIENT_CREDENTIALS ->
                new AuthMaterial.OAuth2ClientCredentials(
                        Objects.requireNonNull(endpoint.tokenUrl()),
                        endpoint.oauthScope(),
                        required(fields, "clientId"),
                        required(fields, "clientSecret"),
                        new TokenCacheKey(team, endpointId, endpointVersion, secret.version()));
        };
    }

    /** Returns the endpoint's extra trusted CA bundle, or {@code null} for the platform trust store. */
    public dev.kairos.http.client.CaBundle caBundle(TeamId team, String name) {
        String pem = control.sql("SELECT pem FROM scheduler.team_ca_bundles WHERE team_id = :team AND name = :name")
                .param("team", team.value())
                .param("name", name)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("The pinned CA bundle is unavailable"));
        return dev.kairos.http.client.CaBundle.parsePem(team + "/" + name, pem);
    }

    /** Opens the team's signing key used by the request signature headers. */
    public TeamSigningKey signingKey(TeamId team) {
        SealedKeyset sealed = keyset(team, KeyPurpose.SIGNING);
        return keysets.signingKey(team, sealed);
    }

    /** Loads a workflow task request from its immutable, team-scoped definition version. */
    public java.util.Optional<dev.kairos.domain.template.RequestTemplate> workflowRequest(
            dev.kairos.proto.v1.Dispatch dispatch) {
        if (dispatch.getTargetTypeValue() != 2) return java.util.Optional.empty();
        java.util.UUID definition = dev.kairos.proto.ProtoIds.toUuid(dispatch.getDefinitionId());
        String encoded = cells.forId(definition)
                .jdbc()
                .sql("""
                SELECT v.spec::text FROM scheduler.workflow_versions v JOIN scheduler.workflows w ON w.workflow_id=v.workflow_id
                WHERE w.team_id=:team AND v.workflow_id=:definition AND v.version=:version
                """)
                .param("team", dev.kairos.proto.ProtoIds.toUuid(dispatch.getTeamId()))
                .param("definition", definition)
                .param("version", dispatch.getDefinitionVersion())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Pinned workflow definition is unavailable"));
        JsonNode task = json.readTree(encoded).path("tasks").path(dispatch.getTaskKey());
        if (!task.isObject()) throw new IllegalStateException("Pinned workflow task is unavailable");
        JsonNode request = task.path("request");
        Map<String, String> path = new LinkedHashMap<>(),
                query = new LinkedHashMap<>(),
                headers = new LinkedHashMap<>();
        request.path("pathParams")
                .properties()
                .forEach(e -> path.put(e.getKey(), e.getValue().asString()));
        request.path("query")
                .properties()
                .forEach(e -> query.put(e.getKey(), e.getValue().asString()));
        request.path("headers")
                .properties()
                .forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
        JsonValue body =
                request.has("body") ? JsonValue.of(json.convertValue(request.get("body"), Object.class)) : null;
        return java.util.Optional.of(new dev.kairos.domain.template.RequestTemplate(path, query, headers, body));
    }

    /** Opens the OUTPUTS keyset when the request snapshot contains encrypted upstream values. */
    public Map<TaskKey, Map<String, OutputValue>> inputs(TeamId team, RunId workflowRunId, byte[] sealedInputs) {
        if (sealedInputs.length == 0) return Map.of();
        JsonNode root;
        try {
            root = json.readTree(sealedInputs);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("The encrypted workflow inputs are invalid", invalid);
        }
        JsonNode tasks = root.path("tasks");
        if (!root.path("v").canConvertToInt()
                || root.path("v").intValue() != 1
                || !tasks.isObject()
                || tasks.size() > 200) {
            throw new IllegalArgumentException("Workflow inputs exceed limits");
        }
        TeamAead outputKeys =
                tasks.isEmpty() ? null : keysets.aead(team, KeyPurpose.OUTPUTS, keyset(team, KeyPurpose.OUTPUTS));
        Map<TaskKey, Map<String, OutputValue>> inputs = new LinkedHashMap<>();
        tasks.properties().forEach(task -> {
            TaskKey taskKey = TaskKey.of(task.getKey());
            if (!task.getValue().isObject() || task.getValue().size() > 256) {
                throw new IllegalArgumentException("Workflow inputs exceed limits");
            }
            Map<String, OutputValue> values = new LinkedHashMap<>();
            task.getValue().properties().forEach(output -> {
                byte[] ciphertext;
                try {
                    ciphertext = Base64.getDecoder().decode(output.getValue().asString());
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("The encrypted workflow inputs are invalid", invalid);
                }
                byte[] plaintext = cipher.decryptOutput(
                        Objects.requireNonNull(outputKeys), workflowRunId, output.getKey(), ciphertext);
                try {
                    Object value = json.readValue(plaintext, Object.class);
                    values.put(output.getKey(), new OutputValue(JsonValue.of(value), true));
                } catch (Exception invalid) {
                    throw new IllegalArgumentException("The encrypted workflow inputs are invalid", invalid);
                } finally {
                    java.util.Arrays.fill(plaintext, (byte) 0);
                }
            });
            inputs.put(taskKey, Map.copyOf(values));
        });
        JsonNode publicTasks = root.path("publicTasks");
        if (!publicTasks.isMissingNode() && (!publicTasks.isObject() || publicTasks.size() > 200))
            throw new IllegalArgumentException("Workflow inputs exceed limits");
        publicTasks.properties().forEach(task -> {
            TaskKey key = TaskKey.of(task.getKey());
            if (!task.getValue().isObject() || task.getValue().size() > 256)
                throw new IllegalArgumentException("Workflow inputs exceed limits");
            Map<String, OutputValue> values = new LinkedHashMap<>(inputs.getOrDefault(key, Map.of()));
            task.getValue().properties().forEach(output -> {
                if (values.containsKey(output.getKey())) throw new IllegalArgumentException("Duplicate workflow input");
                values.put(
                        output.getKey(),
                        new OutputValue(JsonValue.of(json.convertValue(output.getValue(), Object.class)), false));
            });
            inputs.put(key, Map.copyOf(values));
        });
        if (inputs.size() > 200) throw new IllegalArgumentException("Workflow inputs exceed limits");
        return Map.copyOf(inputs);
    }

    /** Returns secret plaintext strings for redaction; callers must drop all references after the call. */
    public java.util.List<String> secretValues(AuthMaterial auth) {
        return auth.secrets();
    }

    /** Encrypts sensitive outputs individually and returns the versioned task-output envelope bytes. */
    public byte[] sealOutputs(TeamId team, RunId workflowRunId, Map<String, OutputValue> outputs) {
        Map<String, String> encrypted = new java.util.TreeMap<>();
        if (outputs.values().stream().noneMatch(OutputValue::sensitive)) return new byte[0];
        TeamAead outputKeys = keysets.aead(team, KeyPurpose.OUTPUTS, keyset(team, KeyPurpose.OUTPUTS));
        outputs.forEach((name, output) -> {
            if (!output.sensitive()) return;
            byte[] plaintext = output.value().json().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                encrypted.put(
                        name,
                        Base64.getEncoder()
                                .encodeToString(cipher.encryptOutput(outputKeys, workflowRunId, name, plaintext)));
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        });
        try {
            return json.writeValueAsBytes(Map.of("v", 1, "values", encrypted));
        } catch (Exception failure) {
            throw new IllegalStateException("Sensitive outputs could not be encoded", failure);
        }
    }

    private Secret secret(TeamId team, String name) {
        return control.sql("""
                SELECT version, kind, ciphertext FROM scheduler.team_secrets
                 WHERE team_id = :team AND name = :name AND disabled_at IS NULL
                 ORDER BY version DESC LIMIT 1
                """)
                .param("team", team.value())
                .param("name", name)
                .query((rs, row) -> new Secret(rs.getInt("version"), rs.getString("kind"), rs.getBytes("ciphertext")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("The endpoint credential is unavailable"));
    }

    private Map<String, String> decryptSecret(TeamId team, String name, Secret secret) {
        byte[] plaintext = cipher.decryptSecret(
                keysets.aead(team, KeyPurpose.SECRETS, keyset(team, KeyPurpose.SECRETS)),
                name,
                secret.version(),
                secret.ciphertext());
        try {
            JsonNode root = json.readTree(plaintext);
            if (!root.isObject()) throw new IllegalArgumentException("The endpoint credential is invalid");
            Map<String, String> fields = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                if (entry.getValue().isString())
                    fields.put(entry.getKey(), entry.getValue().asString());
            });
            return Map.copyOf(fields);
        } catch (Exception invalid) {
            throw new IllegalStateException("The endpoint credential is invalid", invalid);
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
    }

    private SealedKeyset keyset(TeamId team, KeyPurpose purpose) {
        return control.sql("""
                SELECT encrypted_keyset, kek_uri FROM scheduler.team_keysets
                 WHERE team_id = :team AND purpose = :purpose
                """)
                .param("team", team.value())
                .param("purpose", purpose.name())
                .query((rs, row) -> new SealedKeyset(rs.getBytes("encrypted_keyset"), rs.getString("kek_uri")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("The team keyset is unavailable"));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isEmpty()) throw new IllegalStateException("The endpoint credential is invalid");
        return value;
    }

    private record Secret(int version, String kind, byte[] ciphertext) {
        private Secret {
            Objects.requireNonNull(kind, "kind");
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public String toString() {
            return "Secret[version=" + version + ", kind=" + kind + ", ciphertext=<redacted>]";
        }
    }
}
