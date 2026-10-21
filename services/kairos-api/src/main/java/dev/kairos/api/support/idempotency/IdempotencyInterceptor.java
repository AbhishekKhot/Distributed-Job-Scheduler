package dev.kairos.api.support.idempotency;

import dev.kairos.api.security.KairosPrincipal;
import dev.kairos.api.security.web.CurrentPrincipal;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.shard.ShardKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies {@link Idempotent} to a request that carries an {@code Idempotency-Key}, before the controller runs.
 *
 * <p>The key must be 1 to 64 visible ASCII characters (400 otherwise). The request hash is SHA-256 over the method,
 * path, query and body. The record's database follows the operation's {@link IdempotencyScope}; when the location
 * cannot be derived (an invalid name or id), or the caller has no team, the request runs without idempotency and the
 * controller rejects it as usual. A repeat of a finished request is answered here with the stored response, and the
 * controller does not run.
 */
public final class IdempotencyInterceptor implements HandlerInterceptor {

    /** The longest key accepted (LLD section 3.1). */
    public static final int MAX_KEY_LENGTH = 64;

    private static final int MAX_NAME_LENGTH = 128;

    private final IdempotencyService service;
    private final JsonMapper json;

    /**
     * Creates the interceptor.
     *
     * @param service the idempotency rules
     * @param json parses the body to find the resource name
     */
    public IdempotencyInterceptor(IdempotencyService service, JsonMapper json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Idempotent idempotent = method.getMethodAnnotation(Idempotent.class);
        String key = request.getHeader(IdempotencyFilter.HEADER);
        if (idempotent == null || key == null) {
            return true;
        }
        requireValidKey(key);
        Optional<TeamId> team = CurrentPrincipal.get().flatMap(KairosPrincipal::team);
        if (!(request.getAttribute(IdempotencyFilter.BODY_ATTRIBUTE) instanceof byte[] body) || team.isEmpty()) {
            return true;
        }
        Optional<IdempotencyStore.Location> location = location(idempotent.store(), request, team.get(), body);
        if (location.isEmpty()) {
            return true;
        }
        IdempotencyService.Outcome outcome = service.begin(location.get(), team.get(), key, hash(request, body));
        return switch (outcome) {
            case IdempotencyService.Outcome.Proceed proceed -> {
                request.setAttribute(IdempotencyFilter.CLAIM_ATTRIBUTE, proceed.claim());
                yield true;
            }
            case IdempotencyService.Outcome.Replay replay -> {
                replay(response, replay.response());
                yield false;
            }
        };
    }

    /**
     * Checks an {@code Idempotency-Key} value.
     *
     * @param key the header value
     * @throws ValidationFailedException with field {@code Idempotency-Key} if it is empty, too long or has characters
     *     outside visible ASCII
     */
    public static void requireValidKey(String key) {
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw ValidationFailedException.of(IdempotencyFilter.HEADER, FieldError.LENGTH);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw ValidationFailedException.of(IdempotencyFilter.HEADER, FieldError.FORMAT);
            }
        }
    }

    private Optional<IdempotencyStore.Location> location(
            IdempotencyScope scope, HttpServletRequest request, TeamId team, byte[] body) {
        return switch (scope) {
            case CONTROL -> Optional.of(IdempotencyStore.Location.CONTROL);
            case NAME_SHARD -> name(body).map(name -> IdempotencyStore.Location.cell(ShardKey.of(team.value(), name)));
            case PATH_ID_SHARD -> pathId(request).map(id -> IdempotencyStore.Location.cell(KairosId.shardOf(id)));
        };
    }

    private Optional<String> name(byte[] body) {
        try {
            JsonNode name = json.readTree(body).get("name");
            if (name != null && name.isString()) {
                String value = name.asString();
                if (!value.isEmpty() && value.length() <= MAX_NAME_LENGTH) {
                    return Optional.of(value);
                }
            }
        } catch (JacksonException e) {
            // Not JSON: the controller rejects the body.
        }
        return Optional.empty();
    }

    private static Optional<UUID> pathId(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && variables.get("id") instanceof String id) {
            try {
                return Optional.of(KairosId.parse("id", id));
            } catch (ValidationFailedException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static byte[] hash(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            String query = request.getQueryString();
            if (query != null) {
                digest.update(query.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) '\n');
            return digest.digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        stored.headers().forEach(response::setHeader);
        String contentType = stored.contentType();
        if (contentType != null) {
            response.setContentType(contentType);
        }
        String body = stored.body();
        if (body != null) {
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
