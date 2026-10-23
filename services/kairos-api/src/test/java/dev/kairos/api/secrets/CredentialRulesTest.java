package dev.kairos.api.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.kairos.api.secrets.application.CredentialValue;
import dev.kairos.api.secrets.application.ResourceNames;
import dev.kairos.api.secrets.application.SecretLookup;
import dev.kairos.api.secrets.application.SecretSealer;
import dev.kairos.api.secrets.application.SecretStore;
import dev.kairos.api.secrets.application.SecretsUseCase;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Input bounds and bounded optimistic retries, independent of infrastructure. */
class CredentialRulesTest {
    @Test
    void namesAreBoundedBeforeMatchingAndMayUseTheFullSupportedLength() {
        assertThat(ResourceNames.check("a".repeat(128))).hasSize(128);
        for (String name : java.util.List.of("", "a".repeat(129), "../bad", "name with spaces", "bad/name")) {
            assertThatThrownBy(() -> ResourceNames.check(name)).isInstanceOf(ValidationFailedException.class);
        }
        assertThatThrownBy(() -> ResourceNames.check(null)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void repeatedRotationConflictsProduceARetryableErrorWithoutAnUnboundedLoop() {
        var team = TestIds.teamId();
        SecretStore store = mock(SecretStore.class);
        SecretSealer sealer = mock(SecretSealer.class);
        var clock = MutableClock.at(Instant.parse("2026-10-23T00:00:00Z"));
        when(store.find(team, "token"))
                .thenReturn(Optional.of(
                        new SecretLookup.Metadata("token", CredentialValue.Kind.BEARER, 1, clock.instant())));
        when(store.rotate(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(sealer.seal(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(new byte[0]);
        var useCase = new SecretsUseCase(store, sealer, clock);
        assertThatThrownBy(() -> useCase.rotate(
                        team, "system:test", "token", new CredentialValue(null, null, "placeholder", null, null, null)))
                .isInstanceOf(dev.kairos.api.support.problem.ApiException.class);
        verify(store, times(8)).rotate(any(), any(), any(), any(), any());
    }

    @Test
    void versionOverflowFailsBeforeEncryption() {
        var team = TestIds.teamId();
        SecretStore store = mock(SecretStore.class);
        SecretSealer sealer = mock(SecretSealer.class);
        var clock = MutableClock.at(Instant.parse("2026-10-23T00:00:00Z"));
        when(store.find(team, "token"))
                .thenReturn(Optional.of(new SecretLookup.Metadata(
                        "token", CredentialValue.Kind.BEARER, Integer.MAX_VALUE, clock.instant())));
        assertThatThrownBy(() -> new SecretsUseCase(store, sealer, clock)
                        .rotate(
                                team,
                                "system:test",
                                "token",
                                new CredentialValue(null, null, "placeholder", null, null, null)))
                .isInstanceOf(dev.kairos.api.support.problem.ApiException.class);
        verifyNoInteractions(sealer);
    }
}
