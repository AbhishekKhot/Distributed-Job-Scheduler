package dev.kairos.mocktarget.behavior.web;

import dev.kairos.mocktarget.behavior.application.Behavior;
import dev.kairos.mocktarget.behavior.application.CallTracker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.*;

/** Bounded synthetic responses on virtual threads. No request headers or bodies are logged or echoed. */
@RestController
public final class BehaviorController {
    private static final byte[] JSON = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHUNK = new byte[8192];

    static {
        java.util.Arrays.fill(CHUNK, (byte) 'x');
    }

    private final CallTracker tracker;
    private final dev.kairos.mocktarget.behavior.application.VerifyRequest verifier;
    private final int maxBody;

    public BehaviorController(
            CallTracker tracker,
            dev.kairos.mocktarget.behavior.application.VerifyRequest verifier,
            dev.kairos.mocktarget.config.MockTargetProperties properties) {
        this.tracker = tracker;
        this.verifier = verifier;
        maxBody = properties.maxBodyBytes();
    }

    @RequestMapping(
            path = {"/behave", "/behave/{profile}"},
            method = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.PATCH,
                RequestMethod.DELETE,
                RequestMethod.HEAD,
                RequestMethod.OPTIONS
            })
    public void behave(
            @PathVariable(required = false) String profile, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        final Behavior behavior;
        try {
            behavior = Behavior.resolve(profile, Behavior.query(request.getQueryString()));
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            return;
        }
        String key = header(request, "Idempotency-Key");
        if (key.length() > 128
                || key.chars().anyMatch(c -> c < 33 || c > 126)
                || request.getHeaders("Idempotency-Key").asIterator().hasNext()
                        && java.util.Collections.list(request.getHeaders("Idempotency-Key"))
                                        .size()
                                > 1) {
            response.setStatus(400);
            return;
        }
        if (request.getContentLengthLong() > maxBody) {
            response.setHeader("Connection", "close");
            response.setStatus(413);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBody + 1);
        if (body.length > maxBody) {
            response.setHeader("Connection", "close");
            response.setStatus(413);
            return;
        }
        String query = request.getQueryString();
        boolean valid = verifier.verify(
                request.getMethod(),
                request.getRequestURI() + (query == null ? "" : "?" + query),
                key,
                body,
                header(request, "X-Kairos-Timestamp"),
                header(request, "X-Kairos-Signature"));
        if (!tracker.record(key, valid)) {
            response.setStatus(503);
            return;
        }
        if (!valid) {
            response.setStatus(401);
            return;
        }
        if (!matchesAssertions(request, body)) {
            response.setStatus(422);
            return;
        }
        try {
            if (behavior.latencyMs() > 0) Thread.sleep(behavior.latencyMs());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            response.setStatus(503);
            return;
        }
        response.setStatus(ThreadLocalRandom.current().nextDouble() < behavior.failRate() ? 503 : behavior.status());
        response.setHeader("X-Mock-Target-Profile", behavior.profile());
        response.setHeader("Cache-Control", "no-store");
        if (behavior.status() == 204
                || behavior.status() == 304
                || request.getMethod().equals("HEAD")) return;
        int length = behavior.bodyBytes() == null ? JSON.length : behavior.bodyBytes();
        response.setContentType(behavior.bodyBytes() == null ? "application/json" : "application/octet-stream");
        response.setContentLength(length);
        if (behavior.bodyBytes() == null) {
            response.getOutputStream().write(JSON);
            return;
        }
        for (int remaining = length; remaining > 0; remaining -= Math.min(remaining, CHUNK.length))
            response.getOutputStream().write(CHUNK, 0, Math.min(remaining, CHUNK.length));
    }

    /** Optional scenario assertions check rendered values without storing or logging request data. */
    private static boolean matchesAssertions(HttpServletRequest request, byte[] body) {
        String expectedHeader = header(request, "X-Mock-Expect-Header");
        if (!expectedHeader.isEmpty()) {
            int separator = expectedHeader.indexOf('=');
            if (separator < 1 || separator > 64 || expectedHeader.length() > 256) return false;
            String name = expectedHeader.substring(0, separator);
            if (!name.matches("[A-Za-z0-9-]+")) return false;
            if (!header(request, name).equals(expectedHeader.substring(separator + 1))) return false;
        }
        String expectedHash = header(request, "X-Mock-Expect-Body-Sha256");
        if (!expectedHash.isEmpty()) {
            if (!expectedHash.matches("[0-9a-fA-F]{64}")) return false;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
                if (!HexFormat.of().formatHex(digest).equalsIgnoreCase(expectedHash)) return false;
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 unavailable", unavailable);
            }
        }
        return true;
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value;
    }
}
