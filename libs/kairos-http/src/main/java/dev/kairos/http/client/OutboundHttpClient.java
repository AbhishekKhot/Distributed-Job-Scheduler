package dev.kairos.http.client;

import dev.kairos.domain.retry.CallResult;
import dev.kairos.http.policy.TargetDecision;
import dev.kairos.http.policy.TargetNotAllowedException;
import dev.kairos.http.policy.TargetPolicy;
import dev.kairos.http.request.HeaderField;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.http.request.OutboundRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/**
 * Sends one {@link OutboundRequest} to a target and reports a {@link HttpCallOutcome}; never throws for a network,
 * policy or protocol failure.
 *
 * <p>Every call is checked against the {@link TargetPolicy} before anything happens, resolved through the validating
 * resolver, sent over TLS 1.2+ with certificate and hostname verification, and runs the {@link SendObserver} right
 * before the request bytes are written. Redirects are returned as responses, never followed; automatic retries,
 * cookies, authentication challenges and content decompression are off. The response timeout is the request's
 * endpoint timeout, and the whole exchange after the send is bounded by the same duration.
 *
 * <p>Thread-safe; designed to be called from many virtual threads. Built by {@link HttpClientFactory}, which owns and
 * closes it.
 */
public final class OutboundHttpClient {

    /** The {@code User-Agent} sent when the request has none. */
    public static final String USER_AGENT = "kairos";

    private final CloseableHttpClient client;
    private final TargetPolicy policy;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Timeout poolWaitTimeout;
    private final ResponseReader reader;

    OutboundHttpClient(
            CloseableHttpClient client,
            TargetPolicy policy,
            ScheduledExecutorService scheduler,
            Clock clock,
            Timeout poolWaitTimeout,
            ResponseReader reader) {
        this.client = client;
        this.policy = policy;
        this.scheduler = scheduler;
        this.clock = clock;
        this.poolWaitTimeout = poolWaitTimeout;
        this.reader = reader;
    }

    /**
     * Sends a request without a send hook.
     *
     * @param request the request
     * @return the outcome
     */
    public HttpCallOutcome execute(OutboundRequest request) {
        return execute(request, SendObserver.NONE);
    }

    /**
     * Sends a request.
     *
     * @param request the request
     * @param observer called just before the request bytes are written; may veto
     * @return the outcome
     */
    public HttpCallOutcome execute(OutboundRequest request, SendObserver observer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observer, "observer");
        Instant startedAt = clock.instant();
        TargetDecision.Allowed allowed;
        switch (policy.checkUrl(request.url())) {
            case TargetDecision.Allowed ok -> allowed = ok;
            case TargetDecision.Denied denied -> {
                return new HttpCallOutcome.Failed(
                        new CallResult.TargetNotAllowed(),
                        "target not allowed: " + denied.reason(),
                        startedAt,
                        clock.instant());
            }
        }
        HttpUriRequestBase http = toHttpRequest(request);
        CallState state = new CallState(
                observer,
                new SendContext(request.method(), allowed.host(), allowed.port()),
                request.timeout(),
                scheduler,
                http::cancel);
        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(CallState.ATTRIBUTE, state);
        try {
            // The target host comes from the request URI, which passed the policy check above.
            ClassicHttpResponse response = client.executeOpen(null, http, context);
            try {
                return readResponse(request.method(), response, http, startedAt);
            } finally {
                response.close();
            }
        } catch (IOException | RuntimeException e) {
            return failure(e, state, startedAt);
        } finally {
            state.finish();
        }
    }

    /** Closes the connection pool; only the owning factory does this. */
    void close() {
        client.close(CloseMode.GRACEFUL);
    }

    private HttpUriRequestBase toHttpRequest(OutboundRequest request) {
        HttpUriRequestBase http = new HttpUriRequestBase(request.method().name(), request.url());
        for (HeaderField header : request.headers()) {
            http.addHeader(header.name(), header.value());
        }
        if (request.header(HttpHeaders.USER_AGENT).isEmpty()) {
            http.addHeader(HttpHeaders.USER_AGENT, USER_AGENT);
        }
        if (request.bodyLength() > 0) {
            // No content type on the entity: the caller's Content-Type header is sent as is.
            http.setEntity(new ByteArrayEntity(request.body(), null));
        }
        http.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(request.timeout().toMillis()))
                .setConnectionRequestTimeout(poolWaitTimeout)
                .setRedirectsEnabled(false)
                .setCircularRedirectsAllowed(false)
                .setAuthenticationEnabled(false)
                .setExpectContinueEnabled(false)
                .setContentCompressionEnabled(false)
                .build());
        return http;
    }

    private HttpCallOutcome readResponse(
            HttpMethod method, ClassicHttpResponse response, HttpUriRequestBase http, Instant startedAt)
            throws IOException {
        int status = response.getCode();
        if (!HttpCallOutcome.isValidStatus(status)) {
            http.cancel();
            return new HttpCallOutcome.Failed(
                    new CallResult.ConnectionFailed(true), "invalid response status", startedAt, clock.instant());
        }
        List<HeaderField> headers = headers(response.getHeaders());
        HttpEntity entity = response.getEntity();
        if (entity == null || method == HttpMethod.HEAD) {
            return new HttpCallOutcome.Received(status, headers, ResponseBody.empty(), startedAt, clock.instant());
        }
        Header contentType = response.getFirstHeader(HttpHeaders.CONTENT_TYPE);
        ResponseReader.Result result;
        try (InputStream in = entity.getContent()) {
            result = reader.read(in, contentType == null ? null : contentType.getValue());
            if (result instanceof ResponseReader.Result.TooLarge) {
                // Abort before the stream is closed, so the rest of the body is never drained.
                http.cancel();
            }
        }
        return switch (result) {
            case ResponseReader.Result.Complete complete ->
                new HttpCallOutcome.Received(status, headers, complete.body(), startedAt, clock.instant());
            case ResponseReader.Result.TooLarge tooLarge ->
                new HttpCallOutcome.TooLarge(status, headers, startedAt, clock.instant());
        };
    }

    private HttpCallOutcome failure(Exception e, CallState state, Instant startedAt) {
        Instant finishedAt = clock.instant();
        if (state.vetoed()) {
            return new HttpCallOutcome.NotSent(state.vetoReason(), startedAt, finishedAt);
        }
        boolean sent = state.sent();
        CallResult result;
        String detail;
        if (state.deadlineExceeded()) {
            result = new CallResult.TimedOut(true);
            detail = "the call exceeded the endpoint timeout";
        } else if (e instanceof TargetNotAllowedException denied) {
            result = new CallResult.TargetNotAllowed();
            detail = "target not allowed: " + denied.reason();
        } else if (e instanceof UnknownHostException) {
            result = new CallResult.DnsFailed();
            detail = "the host name could not be resolved";
        } else if (!sent && e instanceof SSLException) {
            result = new CallResult.TlsFailed();
            detail = "the TLS handshake or certificate verification failed";
        } else if (e instanceof InterruptedIOException) {
            result = new CallResult.TimedOut(sent);
            detail = sent ? "the response timed out" : "the connection timed out";
        } else {
            result = new CallResult.ConnectionFailed(sent);
            detail = sent
                    ? "the connection failed after the request was sent"
                    : "the connection failed before the request was sent";
        }
        return new HttpCallOutcome.Failed(result, detail, startedAt, finishedAt);
    }

    private static List<HeaderField> headers(Header[] raw) {
        List<HeaderField> headers = new ArrayList<>(raw.length);
        for (Header header : raw) {
            String value = header.getValue() == null ? "" : header.getValue();
            if (HeaderField.isToken(header.getName()) && HeaderField.isValidValue(value)) {
                headers.add(new HeaderField(header.getName(), value));
            }
        }
        return headers;
    }
}
