package dev.kairos.api.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.kairos.api.signing.application.SigningUseCase;
import dev.kairos.api.signing.infrastructure.SigningKeyPruner;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Configuration bounds and resilient periodic cleanup. */
class SigningPropertiesTest {
    @Test
    void durationsMustBePositiveAndBounded() {
        assertThat(new SigningProperties(Duration.ofDays(1), Duration.ofMinutes(1), 100).isDurationsValid())
                .isTrue();
        for (Duration invalid : java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(8))) {
            assertThat(new SigningProperties(invalid, Duration.ofMinutes(1), 100).isDurationsValid())
                    .isFalse();
        }
        assertThat(new SigningProperties(null, null, 100).isDurationsValid()).isFalse();
        assertThat(new SigningProperties(Duration.ofDays(1), Duration.ZERO, 100).isDurationsValid())
                .isFalse();
        assertThat(new SigningProperties(Duration.ofDays(1), Duration.ofDays(2), 100).isDurationsValid())
                .isFalse();
    }

    @Test
    void aFailedCleanupPassDoesNotPreventALaterRetry() {
        SigningUseCase keys = mock(SigningUseCase.class);
        doThrow(new IllegalStateException("unavailable")).doReturn(1).when(keys).pruneExpired(100);
        SigningKeyPruner pruner = new SigningKeyPruner(keys, 100);
        pruner.prune();
        pruner.prune();
        verify(keys, times(2)).pruneExpired(100);
    }
}
