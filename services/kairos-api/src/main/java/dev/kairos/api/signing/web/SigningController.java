package dev.kairos.api.signing.web;

import dev.kairos.api.security.TeamContext;
import dev.kairos.api.security.UserTokenOnly;
import dev.kairos.api.signing.application.SigningUseCase;
import dev.kairos.api.support.problem.Problem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** User-token-only request-signing key rotation (LLD API 23). */
@RestController
@UserTokenOnly
@Tag(name = "Signing keys", description = "Rotate the team's outbound request-signing key.")
public class SigningController {
    private final SigningUseCase keys;

    public SigningController(SigningUseCase keys) {
        this.keys = keys;
    }

    @PostMapping(path = "/v1/team/signing-key:rotate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Rotate the request-signing key",
            description =
                    "Signs new requests with the new version."
                            + " Returns the full key only once. Targets should accept the previous key until previousKeyValidUntil.")
    @ApiResponse(
            responseCode = "200",
            description = "Rotated; full key shown once",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RotatedResponse.class),
                            examples = @ExampleObject(value = """
                            {"signingKey":"ksig_v2_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx","keyVersion":2,
                             "previousKeyValidUntil":"2026-10-15T09:30:00Z"}
                            """)))
    @ApiResponse(
            responseCode = "403",
            description = "User token required, or no team",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                            {"type":"/errors/forbidden-for-api-key","title":"Not allowed for API keys","status":403,
                             "detail":"This operation requires a user access token."}
                            """)))
    public ResponseEntity<RotatedResponse> rotate(TeamContext team) {
        SigningUseCase.Rotated rotated = keys.rotate(team.teamId(), team.actor());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new RotatedResponse(
                        rotated.signingKey().reveal(), rotated.keyVersion(), rotated.previousKeyValidUntil()));
    }

    @Schema(name = "RotatedSigningKey")
    public record RotatedResponse(String signingKey, int keyVersion, Instant previousKeyValidUntil) {
        @Override
        public String toString() {
            return "RotatedSigningKey[keyVersion=" + keyVersion + ", signingKey=<redacted>]";
        }
    }
}
