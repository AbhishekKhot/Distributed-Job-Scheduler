package dev.kairos.api.secrets.web;

import dev.kairos.api.secrets.application.CredentialValue;
import dev.kairos.api.secrets.application.SecretLookup;
import dev.kairos.api.secrets.application.SecretsUseCase;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.support.etag.ETags;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.api.support.problem.Problem;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Team-scoped credential APIs 16–19. Only metadata leaves the application. */
@RestController
@Tag(name = "Secrets", description = "Store and rotate encrypted target credentials; values are never returned.")
public class SecretsController {
    private final SecretsUseCase secrets;
    private final CursorCodec cursors;

    public SecretsController(SecretsUseCase secrets, CursorCodec cursors) {
        this.secrets = secrets;
        this.cursors = cursors;
    }

    @PostMapping(
            path = "/v1/secrets",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Create a secret",
            description = "Creates version 1; encrypts values with the team's SECRETS keyset.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            schema = @Schema(implementation = CreateRequest.class),
                                            examples = @ExampleObject(value = """
                            {"name":"orders-api-token","kind":"BEARER","value":{"token":"<target bearer token>"}}
                            """))))
    @ApiResponse(
            responseCode = "201",
            description = "Created; metadata only",
            headers = @Header(name = "Location", schema = @Schema(type = "string"), description = "/v1/secrets/{name}"),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SecretResponse.class),
                            examples = @ExampleObject(value = """
                            {"name":"orders-api-token","kind":"BEARER","version":1,"createdAt":"2026-10-14T09:30:00Z"}
                            """)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid name, kind or value",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                            {"type":"/errors/validation","title":"Invalid request","status":400,
                             "detail":"`value` must match the kind: BASIC {username, password}, BEARER {token}, API_KEY {key}, OAUTH2_CLIENT {clientId, clientSecret}.",
                             "errors":[{"field":"value.token","code":"required"}]}
                            """)))
    @ApiResponse(
            responseCode = "409",
            description = "Secret already exists",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                            {"type":"/errors/secret-exists","title":"Secret exists","status":409,
                             "detail":"A secret with this name already exists. Use PUT to add a version."}
                            """)))
    public ResponseEntity<SecretResponse> create(TeamContext team, @Valid @RequestBody CreateRequest body) {
        var created = secrets.create(team.teamId(), team.actor(), body.name(), body.kind(), value(body.value(), true));
        return ResponseEntity.created(URI.create("/v1/secrets/" + created.name()))
                .eTag(ETags.of(created.version()))
                .header("Cache-Control", "no-store")
                .body(SecretResponse.of(created));
    }

    @GetMapping(path = "/v1/secrets", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List secrets",
            description = "Lists current metadata alphabetically, without credential values or ciphertext.")
    @ApiResponse(
            responseCode = "200",
            description = "One page of metadata",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                    {"items":[{"name":"orders-api-token","kind":"BEARER","version":2,"createdAt":"2026-10-14T09:30:00Z"}],"nextCursor":null}
                    """)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid limit or cursor",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/validation","title":"Invalid request","status":400,"detail":"One or more fields are invalid.","errors":[{"field":"cursor","code":"format"}]}
                    """)))
    public Page<SecretResponse> list(
            TeamContext team,
            @Parameter(description = "Page size 1–200; default 50.")
                    @RequestParam(name = "limit", required = false)
                    @Nullable Integer limit,
            @Parameter(description = "Opaque nextCursor, bound to this team and list.")
                    @RequestParam(name = "cursor", required = false)
                    @Nullable String cursor) {
        String after = null;
        if (cursor != null) {
            List<String> decoded = cursors.decode("secrets", team.teamId(), cursor);
            if (decoded.size() != 1
                    || decoded.getFirst().length() > 128
                    || !decoded.getFirst().matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) {
                throw ValidationFailedException.of("cursor", FieldError.FORMAT);
            }
            after = decoded.getFirst();
        }
        SecretsUseCase.Result page = secrets.list(team.teamId(), after, PageLimit.resolve(limit));
        String next = page.next();
        return new Page<>(
                page.items().stream().map(SecretResponse::of).toList(),
                next == null ? null : cursors.encode("secrets", team.teamId(), List.of(next)));
    }

    @PutMapping(
            path = "/v1/secrets/{name}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Rotate a secret value",
            description =
                    "Adds a new immutable version using the existing kind."
                            + " Concurrent rotations serialize; If-Match is not required. Executor caches refresh within 60 seconds.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            schema = @Schema(implementation = RotateRequest.class),
                                            examples = @ExampleObject(value = """
                            {"value":{"token":"<new target bearer token>"}}
                            """))))
    @ApiResponse(
            responseCode = "200",
            description = "Rotated; metadata only",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SecretResponse.class),
                            examples = @ExampleObject(value = """
                    {"name":"orders-api-token","kind":"BEARER","version":2,"createdAt":"2026-10-14T09:30:00Z"}
                    """)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid value",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/validation","title":"Invalid request","status":400,"detail":"One or more fields are invalid.","errors":[{"field":"value.token","code":"required"}]}
                    """)))
    @ApiResponse(
            responseCode = "404",
            description = "Secret not found",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/not-found","title":"Not found","status":404,"detail":"The secret does not exist."}
                    """)))
    public ResponseEntity<SecretResponse> rotate(
            TeamContext team,
            @Parameter(description = "Secret name.") @PathVariable("name") String name,
            @RequestBody RotateRequest body) {
        var updated = secrets.rotate(team.teamId(), team.actor(), name, value(body.value(), false));
        return ResponseEntity.ok()
                .eTag(ETags.of(updated.version()))
                .header("Cache-Control", "no-store")
                .body(SecretResponse.of(updated));
    }

    @DeleteMapping("/v1/secrets/{name}")
    @Operation(
            summary = "Delete a secret",
            description = "Deletes every version; refuses deletion while an endpoint references the name.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Secret not found",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/not-found","title":"Not found","status":404,"detail":"The secret does not exist."}
                    """)))
    @ApiResponse(
            responseCode = "409",
            description = "Secret in use",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/secret-in-use","title":"Secret in use","status":409,"detail":"The secret is used by endpoint orders-create."}
                    """)))
    public ResponseEntity<Void> delete(
            TeamContext team, @Parameter(description = "Secret name.") @PathVariable("name") String name) {
        secrets.delete(team.teamId(), team.actor(), name);
        return ResponseEntity.noContent().build();
    }

    @Schema(name = "CreateSecretRequest")
    public record CreateRequest(
            @NotNull String name,
            @Nullable String kind,

            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @Nullable CredentialRequest value) {
        @Override
        public String toString() {
            return "CreateSecretRequest[<redacted>]";
        }
    }

    @Schema(name = "RotateSecretRequest")
    public record RotateRequest(
            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @Nullable CredentialRequest value) {
        @Override
        public String toString() {
            return "RotateSecretRequest[<redacted>]";
        }
    }

    private static @Nullable CredentialValue value(@Nullable CredentialRequest request, boolean creating) {
        return request == null ? null : request.value(creating);
    }

    /** Preserve JSON types until validation; a number or boolean must never become a credential string. */
    @Schema(name = "CredentialValue")
    public record CredentialRequest(
            @Schema(type = "string") @Nullable JsonNode username,
            @Schema(type = "string") @Nullable JsonNode password,
            @Schema(type = "string") @Nullable JsonNode token,
            @Schema(type = "string") @Nullable JsonNode key,
            @Schema(type = "string") @Nullable JsonNode clientId,
            @Schema(type = "string") @Nullable JsonNode clientSecret) {
        CredentialValue value(boolean creating) {
            return new CredentialValue(
                    text(username, "username", creating),
                    text(password, "password", creating),
                    text(token, "token", creating),
                    text(key, "key", creating),
                    text(clientId, "clientId", creating),
                    text(clientSecret, "clientSecret", creating));
        }

        private static @Nullable String text(@Nullable JsonNode node, String field, boolean creating) {
            if (node == null) return null;
            if (!node.isString())
                throw CredentialValue.invalid(
                        List.of(new FieldError(
                                "value." + field, node.isNull() ? FieldError.REQUIRED : FieldError.FORMAT)),
                        creating);
            return node.asString();
        }

        @Override
        public String toString() {
            return "CredentialRequest[<redacted>]";
        }
    }

    @Schema(name = "SecretMetadata")
    public record SecretResponse(String name, CredentialValue.Kind kind, int version, Instant createdAt) {
        static SecretResponse of(SecretLookup.Metadata metadata) {
            return new SecretResponse(metadata.name(), metadata.kind(), metadata.version(), metadata.createdAt());
        }
    }
}
