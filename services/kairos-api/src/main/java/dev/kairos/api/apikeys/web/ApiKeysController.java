package dev.kairos.api.apikeys.web;

import dev.kairos.api.apikeys.application.ApiKeyStore.PageKey;
import dev.kairos.api.apikeys.application.ApiKeysUseCase;
import dev.kairos.api.apikeys.web.ApiKeyDtos.CreateRequest;
import dev.kairos.api.apikeys.web.ApiKeyDtos.CreatedResponse;
import dev.kairos.api.apikeys.web.ApiKeyDtos.MetadataResponse;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.security.UserTokenOnly;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.api.support.problem.Problem;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User-token-only management of the caller's team's API keys (LLD section 3.4). */
@RestController
@UserTokenOnly
@Tag(name = "API keys", description = "Create, list and revoke team API keys using a user access token.")
@ApiResponse(
        responseCode = "403",
        description = "User token required, or no team",
        content =
                @Content(
                        mediaType = Problem.MEDIA_TYPE,
                        schema = @Schema(implementation = Problem.class),
                        examples = @ExampleObject(name = "forbidden-for-api-key", value = ApiKeyExamples.FORBIDDEN)))
public class ApiKeysController {
    private static final String LIST = "api-keys";
    private final ApiKeysUseCase keys;
    private final CursorCodec cursors;

    public ApiKeysController(ApiKeysUseCase keys, CursorCodec cursors) {
        this.keys = keys;
        this.cursors = cursors;
    }

    @PostMapping(
            path = "/v1/api-keys",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Create an API key",
            description = "Creates a team API key with optional future expiry. Returns the full key only once;"
                    + " only its HMAC and pepper version are stored. Requires a user access token.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            schema = @Schema(implementation = CreateRequest.class),
                                            examples = @ExampleObject(value = ApiKeyExamples.REQUEST))))
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            headers =
                    @Header(name = "Location", description = "/v1/api-keys/{keyId}", schema = @Schema(type = "string")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreatedResponse.class),
                            examples = @ExampleObject(value = ApiKeyExamples.CREATED)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = ApiKeyExamples.VALIDATION)))
    public ResponseEntity<CreatedResponse> create(
            TeamContext team, UserPrincipal user, @Valid @RequestBody CreateRequest body) {
        CreatedResponse created =
                CreatedResponse.of(keys.create(team.teamId(), user.user(), body.name(), body.expiresAt()));
        return ResponseEntity.created(URI.create("/v1/api-keys/" + created.keyId()))
                .header("Cache-Control", "no-store")
                .body(created);
    }

    @GetMapping(path = "/v1/api-keys", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List API keys",
            description = "Lists metadata only, including revoked and expired keys,"
                    + " newest first. Requires a user access token.")
    @ApiResponse(
            responseCode = "200",
            description = "One page of key metadata",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = ApiKeyExamples.LIST)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid limit or cursor",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = ApiKeyExamples.VALIDATION)))
    public Page<MetadataResponse> list(
            TeamContext team,
            @Parameter(description = "Page size, 1–200; default 50.")
                    @RequestParam(name = "limit", required = false)
                    @Nullable Integer limit,
            @Parameter(description = "Opaque value from nextCursor.")
                    @RequestParam(name = "cursor", required = false)
                    @Nullable String cursor) {
        int size = PageLimit.resolve(limit);
        PageKey after = cursor == null ? null : decode(team.teamId(), cursor);
        ApiKeysUseCase.KeyPage page = keys.list(team.teamId(), after, size);
        PageKey next = page.next();
        return new Page<>(
                page.items().stream().map(MetadataResponse::of).toList(),
                next == null
                        ? null
                        : cursors.encode(
                                LIST, team.teamId(), List.of(next.createdAt().toString(), next.keyId())));
    }

    @DeleteMapping("/v1/api-keys/{keyId}")
    @Operation(
            summary = "Revoke an API key",
            description = "Revokes a team key. Cached authentication lookups expire"
                    + " within 60 seconds. Repeating revocation succeeds. Requires a user access token.")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(
            responseCode = "404",
            description = "The API key does not exist",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = ApiKeyExamples.NOT_FOUND)))
    public ResponseEntity<Void> revoke(
            TeamContext team,
            UserPrincipal user,
            @Parameter(description = "Public key id, 16 alphanumeric characters.") @PathVariable("keyId")
                    String keyId) {
        keys.revoke(team.teamId(), user.user(), keyId);
        return ResponseEntity.noContent().build();
    }

    private PageKey decode(TeamId team, String cursor) {
        List<String> values = cursors.decode(LIST, team, cursor);
        if (values.size() != 2 || !ApiKeysUseCase.validKeyId(values.get(1))) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
        try {
            return new PageKey(Instant.parse(values.getFirst()), values.get(1));
        } catch (DateTimeParseException e) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
    }
}
