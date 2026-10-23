package dev.kairos.api.cabundles.web;

import dev.kairos.api.cabundles.application.CaBundleLookup;
import dev.kairos.api.cabundles.application.CaBundlesUseCase;
import dev.kairos.api.security.TeamContext;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** LLD APIs 20–22; accepts public CA certificates only, and returns metadata rather than PEM. */
@RestController
@Tag(name = "CA bundles", description = "Manage the team's private trust anchors.")
public class CaBundlesController {
    private final CaBundlesUseCase bundles;
    private final CursorCodec cursors;

    public CaBundlesController(CaBundlesUseCase bundles, CursorCodec cursors) {
        this.bundles = bundles;
        this.cursors = cursors;
    }

    @PostMapping(
            path = "/v1/ca-bundles",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Add a CA bundle",
            description = "Validates 1–10 current CA certificates in PEM, at most 64 KiB."
                    + " Private keys, end-entity certificates and extra content are rejected.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            schema = @Schema(implementation = CreateRequest.class),
                                            examples = @ExampleObject(value = """
                            {"name":"corp-internal-ca","pem":"-----BEGIN CERTIFICATE-----\\nMIIB...\\n-----END CERTIFICATE-----"}
                            """))))
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            headers =
                    @Header(
                            name = "Location",
                            schema = @Schema(type = "string"),
                            description = "/v1/ca-bundles/{name}"),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BundleResponse.class),
                            examples = @ExampleObject(value = """
                    {"name":"corp-internal-ca","createdAt":"2026-10-14T09:30:00Z"}
                    """)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid name or CA certificates",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/validation","title":"Invalid request","status":400,"detail":"The PEM must contain one or more valid CA certificates.","errors":[{"field":"pem","code":"invalid-certificate"}]}
                    """)))
    @ApiResponse(
            responseCode = "409",
            description = "CA bundle already exists",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/ca-bundle-exists","title":"CA bundle exists","status":409,"detail":"A CA bundle with this name already exists."}
                    """)))
    public ResponseEntity<BundleResponse> create(TeamContext team, @Valid @RequestBody CreateRequest body) {
        var created = bundles.create(team.teamId(), team.actor(), body.name(), body.pem());
        return ResponseEntity.created(URI.create("/v1/ca-bundles/" + created.name()))
                .body(BundleResponse.of(created));
    }

    @GetMapping(path = "/v1/ca-bundles", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List CA bundles",
            description = "Lists names and creation times alphabetically; PEM is not returned.")
    @ApiResponse(
            responseCode = "200",
            description = "One page of bundle metadata",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                    {"items":[{"name":"corp-internal-ca","createdAt":"2026-10-14T09:30:00Z"}],"nextCursor":null}
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
    public Page<BundleResponse> list(
            TeamContext team,
            @Parameter(description = "Page size 1–200; default 50.")
                    @RequestParam(name = "limit", required = false)
                    @Nullable Integer limit,
            @Parameter(description = "Opaque nextCursor, bound to this team and list.")
                    @RequestParam(name = "cursor", required = false)
                    @Nullable String cursor) {
        String after = null;
        if (cursor != null) {
            List<String> decoded = cursors.decode("ca-bundles", team.teamId(), cursor);
            if (decoded.size() != 1
                    || decoded.getFirst().length() > 128
                    || !decoded.getFirst().matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) {
                throw ValidationFailedException.of("cursor", FieldError.FORMAT);
            }
            after = decoded.getFirst();
        }
        CaBundlesUseCase.Result page = bundles.list(team.teamId(), after, PageLimit.resolve(limit));
        String next = page.next();
        return new Page<>(
                page.items().stream().map(BundleResponse::of).toList(),
                next == null ? null : cursors.encode("ca-bundles", team.teamId(), List.of(next)));
    }

    @DeleteMapping("/v1/ca-bundles/{name}")
    @Operation(
            summary = "Delete a CA bundle",
            description = "Refuses deletion while an endpoint references the bundle.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(
            responseCode = "404",
            description = "CA bundle not found",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/not-found","title":"Not found","status":404,"detail":"The CA bundle does not exist."}
                    """)))
    @ApiResponse(
            responseCode = "409",
            description = "CA bundle in use",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                    {"type":"/errors/ca-bundle-in-use","title":"CA bundle in use","status":409,"detail":"The CA bundle is used by endpoint orders-create."}
                    """)))
    public ResponseEntity<Void> delete(
            TeamContext team, @Parameter(description = "CA bundle name.") @PathVariable("name") String name) {
        bundles.delete(team.teamId(), team.actor(), name);
        return ResponseEntity.noContent().build();
    }

    @Schema(name = "CreateCaBundleRequest")
    public record CreateRequest(
            @NotNull String name, @Nullable String pem) {
        @Override
        public String toString() {
            return "CreateCaBundleRequest[pem=<redacted>]";
        }
    }

    @Schema(name = "CaBundleMetadata")
    public record BundleResponse(String name, Instant createdAt) {
        static BundleResponse of(CaBundleLookup.Metadata metadata) {
            return new BundleResponse(metadata.name(), metadata.createdAt());
        }
    }
}
