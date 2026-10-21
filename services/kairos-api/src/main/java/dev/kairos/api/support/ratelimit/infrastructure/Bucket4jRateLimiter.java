package dev.kairos.api.support.ratelimit.infrastructure;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.problem.ProblemWriter;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.api.support.ratelimit.RateLimitPolicy;
import dev.kairos.api.support.ratelimit.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RateLimiter} on Bucket4j token buckets in Redis.
 *
 * <p>Each limit and subject has one bucket at {@code kairos:rl:<limit>:<hash>}, where the hash is the first 128 bits
 * of SHA-256 of the subject, so Redis never holds an email or IP address. A bucket refills greedily over its period
 * and expires once it would be full again. Any Redis failure or timeout fails closed with 503. Thread-safe.
 */
public final class Bucket4jRateLimiter implements RateLimiter {

    /** The prefix of every bucket key (LLD section 4.3). */
    public static final String KEY_PREFIX = "kairos:rl:";

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jRateLimiter.class);
    private static final int SUBJECT_HASH_BYTES = 16;

    private final RateLimitRedis redis;
    private final Map<RateLimit, BucketConfiguration> configurations;

    /**
     * Creates the limiter.
     *
     * @param redis the Redis connection
     * @param policies the policy of every limit
     * @throws IllegalArgumentException if a limit has no policy
     */
    public Bucket4jRateLimiter(RateLimitRedis redis, Map<RateLimit, RateLimitPolicy> policies) {
        this.redis = Objects.requireNonNull(redis, "redis");
        Map<RateLimit, BucketConfiguration> built = new EnumMap<>(RateLimit.class);
        for (RateLimit limit : RateLimit.values()) {
            RateLimitPolicy policy = policies.get(limit);
            if (policy == null) {
                throw new IllegalArgumentException("No rate-limit policy for " + limit);
            }
            built.put(
                    limit,
                    BucketConfiguration.builder()
                            .addLimit(Bandwidth.builder()
                                    .capacity(policy.capacity())
                                    .refillGreedy(policy.capacity(), policy.period())
                                    .build())
                            .build());
        }
        this.configurations = built;
    }

    @Override
    public void consume(RateLimit limit, String subject) {
        BucketConfiguration configuration = configurations.get(limit);
        ConsumptionProbe probe;
        try {
            probe = redis.buckets()
                    .builder()
                    .build(key(limit, subject), () -> configuration)
                    .tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            LOG.warn(
                    "Rate limit {} could not be checked, refusing the request: {}",
                    limit.key(),
                    e.getClass().getSimpleName());
            throw ApiException.unavailable(ProblemWriter.DEFAULT_UNAVAILABLE_RETRY);
        }
        if (!probe.isConsumed()) {
            throw ApiException.rateLimited(Duration.ofNanos(Math.max(1, probe.getNanosToWaitForRefill())));
        }
    }

    /**
     * Returns the Redis key of a bucket.
     *
     * @param limit the limit
     * @param subject the limited subject
     * @return {@code kairos:rl:<limit>:<32 hex characters>}
     */
    public static String key(RateLimit limit, String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + limit.key() + ":" + HexFormat.of().formatHex(digest, 0, SUBJECT_HASH_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }
}
