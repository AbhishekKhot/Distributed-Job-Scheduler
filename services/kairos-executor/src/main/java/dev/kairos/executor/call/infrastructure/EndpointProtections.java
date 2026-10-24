package dev.kairos.executor.call.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.kairos.http.client.HttpCallOutcome;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Per-endpoint Resilience4j circuit breakers and rate limiters. */
public final class EndpointProtections {

    private static final Duration BREAKER_OPEN = Duration.ofSeconds(30);
    private static final Duration RATE_WINDOW = Duration.ofSeconds(1);

    private final CircuitBreakerRegistry breakers;
    private final Cache<UUID, CircuitBreaker> circuitBreakers =
            Caffeine.newBuilder().maximumSize(20_000).build();
    private final Cache<RateKey, RateLimiter> rateLimiters =
            Caffeine.newBuilder().maximumSize(20_000).build();
    private final MeterRegistry meters;

    /** Creates the circuit breaker and rate limiter registry. */
    public EndpointProtections(MeterRegistry meters) {
        this.meters = Objects.requireNonNull(meters, "meters");
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .waitDurationInOpenState(BREAKER_OPEN)
                .permittedNumberOfCallsInHalfOpenState(10)
                .build();
        this.breakers = CircuitBreakerRegistry.of(config);
    }

    /** Tries the breaker and rate limit before resolving credentials or calling the endpoint. */
    public Admission acquire(UUID endpointId, int ratePerSec) {
        CircuitBreaker breaker = circuitBreakers.get(endpointId, this::newBreaker);
        if (!breaker.tryAcquirePermission()) return new Admission(false, 1_000, breaker, false);
        if (ratePerSec <= 0) return new Admission(true, 0, breaker, true);
        RateKey rateKey = new RateKey(endpointId, ratePerSec);
        RateLimiter limiter = rateLimiters.get(rateKey, key -> newLimiter(key.endpoint(), key.ratePerSec()));
        if (!limiter.acquirePermission()) return new Admission(false, RATE_WINDOW.toMillis(), breaker, true);
        return new Admission(true, 0, breaker, true);
    }

    /** Records one HTTP outcome for breaker statistics; a send veto returns its permission untouched. */
    public void complete(Admission admission, HttpCallOutcome outcome, boolean successful) {
        if (!admission.breakerPermission()) return;
        if (outcome instanceof HttpCallOutcome.NotSent) {
            admission.breaker().releasePermission();
            return;
        }
        long elapsed = Math.max(
                0, Duration.between(outcome.startedAt(), outcome.finishedAt()).toMillis());
        if (successful) admission.breaker().onSuccess(elapsed, TimeUnit.MILLISECONDS);
        else admission.breaker().onError(elapsed, TimeUnit.MILLISECONDS, new EndpointCallFailure());
    }

    /** Releases a breaker permission when request construction failed before a call began. */
    public void abandon(Admission admission) {
        if (admission.breakerPermission()) admission.breaker().releasePermission();
    }

    private CircuitBreaker newBreaker(UUID endpointId) {
        String name = endpointId.toString();
        CircuitBreaker breaker = breakers.circuitBreaker(name);
        Gauge.builder("kairos.executor.breaker.state", breaker, EndpointProtections::stateCode)
                .tag("endpoint", name)
                .register(meters);
        breaker.getEventPublisher()
                .onStateTransition(event -> meters.counter(
                                "kairos.executor.breaker.state_change",
                                "endpoint",
                                name,
                                "from",
                                event.getStateTransition().getFromState().name().toLowerCase(),
                                "to",
                                event.getStateTransition().getToState().name().toLowerCase())
                        .increment());
        return breaker;
    }

    private RateLimiter newLimiter(UUID endpointId, int ratePerSec) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(ratePerSec)
                .limitRefreshPeriod(RATE_WINDOW)
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of(endpointId.toString(), config);
    }

    private static int stateCode(CircuitBreaker breaker) {
        return switch (breaker.getState()) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
            case FORCED_OPEN -> 3;
            case DISABLED -> 4;
            case METRICS_ONLY -> 5;
        };
    }

    private record RateKey(UUID endpoint, int ratePerSec) {}

    /** Gate result; the breaker permission is released or recorded exactly once. */
    public record Admission(boolean allowed, long retryAfterMillis, CircuitBreaker breaker, boolean breakerPermission) {
        /** Creates a validated admission result. */
        public Admission {
            Objects.requireNonNull(breaker, "breaker");
            if (retryAfterMillis < 0) throw new IllegalArgumentException("retryAfterMillis cannot be negative");
        }
    }

    private static final class EndpointCallFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private EndpointCallFailure() {
            super("The endpoint call failed");
        }
    }
}
