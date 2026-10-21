/**
 * Rate limits backed by Redis token buckets ({@code kairos:rl:*}, LLD section 4.3): login per IP and per account,
 * signup per IP, team creation and member addition per user, credential changes per user, endpoint tests per team
 * and the per-team API quota. Limits fail closed: when Redis cannot be reached the request gets 503 with
 * {@code Retry-After}. The port is plain Java so use cases can call it.
 */
@NullMarked
package dev.kairos.api.support.ratelimit;

import org.jspecify.annotations.NullMarked;
