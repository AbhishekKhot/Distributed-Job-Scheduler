package dev.kairos.api.apikeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.kairos.api.apikeys.application.ApiKeyStore;
import dev.kairos.api.apikeys.application.ApiKeysUseCase;
import dev.kairos.api.apikeys.infrastructure.ThrottledApiKeyUsage;
import dev.kairos.api.apikeys.web.ApiKeyDtos;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.SecretValue;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Verifies write coalescing under concurrency, retry after failure, and diagnostic redaction. */
class ApiKeyUsageTest {
    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-23T09:00:00Z"));
    private final ApiKeyStore store = mock(ApiKeyStore.class);
    private final TeamId team = TestIds.teamId();

    @Test
    void concurrentRequestsShareOneUpdatePerMinuteAndKeysAreIndependent() {
        ThrottledApiKeyUsage usage = new ThrottledApiKeyUsage(store, clock, 100);
        List<CompletableFuture<Void>> calls = IntStream.range(0, 20)
                .mapToObj(i -> CompletableFuture.runAsync(() -> usage.record(team, "1234567890abcdef")))
                .toList();
        calls.forEach(CompletableFuture::join);
        usage.record(team, "abcdef1234567890");
        verify(store).recordUse(team, "1234567890abcdef", clock.instant());
        verify(store).recordUse(team, "abcdef1234567890", clock.instant());
        clock.advance(Duration.ofSeconds(59));
        usage.record(team, "1234567890abcdef");
        verify(store, times(1)).recordUse(eq(team), eq("1234567890abcdef"), any());
        clock.advance(Duration.ofSeconds(1));
        usage.record(team, "1234567890abcdef");
        verify(store, times(2)).recordUse(eq(team), eq("1234567890abcdef"), any());
    }

    @Test
    void failedWritesAreRetriedOnTheNextRequest() {
        ThrottledApiKeyUsage usage = new ThrottledApiKeyUsage(store, clock, 100);
        doThrow(new IllegalStateException("unavailable"))
                .doNothing()
                .when(store)
                .recordUse(eq(team), eq("1234567890abcdef"), any());
        assertThatThrownBy(() -> usage.record(team, "1234567890abcdef")).isInstanceOf(IllegalStateException.class);
        usage.record(team, "1234567890abcdef");
        usage.record(team, "1234567890abcdef");
        verify(store, times(2)).recordUse(eq(team), eq("1234567890abcdef"), any());
    }

    @Test
    void secretBearingValuesNeverPrintTheKey() {
        String canary = "test-canary-" + java.util.UUID.randomUUID();
        ApiKeysUseCase.Created created =
                new ApiKeysUseCase.Created("1234567890abcdef", "ci", SecretValue.of(canary), null, clock.instant());
        ApiKeyDtos.CreatedResponse response =
                new ApiKeyDtos.CreatedResponse(created.keyId(), created.name(), canary, null, clock.instant());
        assertThat(created.toString().contains(canary)).isFalse();
        assertThat(response.toString().contains(canary)).isFalse();
    }
}
