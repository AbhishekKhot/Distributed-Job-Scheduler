package dev.kairos.api.accounts.web;

import dev.kairos.security.token.AccessTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 7, {@code GET /.well-known/jwks.json} (LLD section 3.3): the public keys that verify access tokens, for services
 * that check Kairos tokens themselves. Public, and cacheable for 5 minutes: a rotation keeps the previous key until
 * the tokens it signed expire, so a cached set stays valid.
 */
@RestController
@Tag(name = "Accounts", description = "Sign up, log in, refresh and revoke tokens, and manage the caller's profile.")
public class JwksController {

    /** The caching policy of the key set (LLD section 3.3). */
    static final String CACHE_CONTROL = "public, max-age=300";

    private final AccessTokenService tokens;

    /**
     * Creates the controller.
     *
     * @param tokens holds the signing keys
     */
    public JwksController(AccessTokenService tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /**
     * API 7: the token signing keys.
     *
     * @param response the HTTP response, whose default {@code no-store} is replaced
     * @return the JWK Set with public keys only
     */
    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get token signing keys",
            description = "Returns the public keys (JWK Set, ES256) that verify access tokens.")
    @ApiResponse(
            responseCode = "200",
            description = "The key set",
            headers = @Header(name = "Cache-Control", description = CACHE_CONTROL, schema = @Schema(type = "string")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "object"),
                            examples = @ExampleObject(name = "keys", value = AccountsExamples.JWKS_RESPONSE)))
    public Map<String, Object> jwks(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        return tokens.jwks();
    }
}
