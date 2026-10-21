package dev.kairos.api;

import dev.kairos.api.security.KairosPrincipal;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.security.UserTokenOnly;
import dev.kairos.api.support.etag.ETags;
import dev.kairos.api.support.etag.IfMatch;
import dev.kairos.api.support.idempotency.IdempotencyScope;
import dev.kairos.api.support.idempotency.Idempotent;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.api.support.web.ClientAddresses;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only operations that exercise the API foundation: error mapping, team context, user-token checks, ETags,
 * idempotency, cursors and rate limits. Loaded only when {@code kairos.test.probe-controller=true}, so the exported
 * OpenAPI document never contains it.
 */
@RestController
@RequestMapping("/v1/probe")
@ConditionalOnProperty(name = "kairos.test.probe-controller", havingValue = "true")
public class ProbeController {

    /** How many times an idempotent probe ran, to prove replays do not run it again. */
    public static final AtomicInteger EXECUTIONS = new AtomicInteger();

    private final CursorCodec cursors;
    private final RateLimiter limiter;
    private final ClientAddresses clients;

    ProbeController(CursorCodec cursors, RateLimiter limiter, ClientAddresses clients) {
        this.cursors = cursors;
        this.limiter = limiter;
        this.clients = clients;
    }

    @GetMapping("/team")
    Map<String, String> team(TeamContext team) {
        return Map.of("team", team.teamId().value().toString(), "actor", team.actor());
    }

    @GetMapping("/me")
    Map<String, String> me(KairosPrincipal principal) {
        return Map.of("actor", principal.actor());
    }

    @UserTokenOnly
    @PostMapping("/user-only")
    ResponseEntity<Void> userOnly(TeamContext team) {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validated")
    Map<String, String> validated(@Valid @RequestBody ProbeBody body) {
        return Map.of("name", body.name());
    }

    @GetMapping("/limited")
    Map<String, Integer> limited(@RequestParam(name = "count") @Min(1) @Max(5) int count) {
        return Map.of("count", count);
    }

    @GetMapping("/header")
    Map<String, String> header(@RequestHeader("X-Probe") String probe) {
        return Map.of("ok", "true");
    }

    @GetMapping("/typed/{id}")
    Map<String, String> typed(@PathVariable("id") UUID id) {
        return Map.of("ok", "true");
    }

    @GetMapping("/conflict")
    void conflict() {
        throw new ConflictException(ErrorCode.JOB_NAME_TAKEN, "A job with this name already exists in the team.");
    }

    @GetMapping("/missing")
    void missing() {
        throw new NotFoundException("The job does not exist.");
    }

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("internal detail that must not leak");
    }

    @GetMapping("/database-down")
    void databaseDown() {
        throw new CannotGetJdbcConnectionException("connection refused");
    }

    @PutMapping("/versioned/{id}")
    ResponseEntity<Map<String, Long>> versioned(
            @PathVariable("id") String id, @IfMatch long version, TeamContext team) {
        ETags.requireVersion(version, 3);
        return ResponseEntity.ok().eTag(ETags.of(4)).body(Map.of("version", 4L));
    }

    @Idempotent(store = IdempotencyScope.NAME_SHARD)
    @PostMapping("/jobs")
    ResponseEntity<Map<String, Object>> createJob(@RequestBody Map<String, Object> body, TeamContext team) {
        int execution = EXECUTIONS.incrementAndGet();
        if (Boolean.TRUE.equals(body.get("fail"))) {
            throw new ConflictException(ErrorCode.JOB_NAME_TAKEN, "A job with this name already exists in the team.");
        }
        String id = UUID.randomUUID().toString();
        return ResponseEntity.created(URI.create("/v1/jobs/" + id))
                .eTag(ETags.of(1))
                .body(Map.of("id", id, "execution", execution, "name", String.valueOf(body.get("name"))));
    }

    @Idempotent(store = IdempotencyScope.PATH_ID_SHARD)
    @PostMapping("/jobs/{id}/runnow")
    ResponseEntity<Map<String, Object>> runNow(@PathVariable("id") String id, TeamContext team) {
        int execution = EXECUTIONS.incrementAndGet();
        return ResponseEntity.status(202).body(Map.of("execution", execution));
    }

    @Idempotent(store = IdempotencyScope.CONTROL)
    @PostMapping("/batch")
    ResponseEntity<Map<String, Object>> batch(@RequestBody Map<String, Object> body, TeamContext team) {
        int execution = EXECUTIONS.incrementAndGet();
        return ResponseEntity.ok(Map.of("execution", execution));
    }

    @GetMapping("/page")
    Page<String> page(
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "cursor", required = false) @Nullable String cursor,
            TeamContext team) {
        int size = PageLimit.resolve(limit);
        List<String> after = cursor == null ? List.of() : cursors.decode("probe", team.teamId(), cursor);
        return new Page<>(
                List.of("size=" + size, "after=" + after), cursors.encode("probe", team.teamId(), List.of("k1", "k2")));
    }

    @GetMapping("/login-like")
    Map<String, String> loginLike(HttpServletRequest request, TeamContext team) {
        limiter.consume(RateLimit.LOGIN_PER_IP, team.teamId() + "|" + clients.clientAddress(request));
        return Map.of("ok", "true");
    }

    /**
     * A validated request body.
     *
     * @param name a short name
     * @param count a small number
     */
    record ProbeBody(
            @NotBlank @Size(max = 10) String name,
            @Min(1) @Max(5) @Nullable Integer count) {}
}
