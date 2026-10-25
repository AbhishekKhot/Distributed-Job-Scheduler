package dev.kairos.maintenance.commands.application;

import dev.kairos.security.SecretValue;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.password.PasswordPolicy;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

/** Generates credentials with a CSPRNG and reveals them only after a successful commit. */
public final class OperatorService {
    private final OperatorStore store;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final SecureRandom random;
    private final Clock clock;

    public OperatorService(
            OperatorStore store, PasswordHasher hasher, PasswordPolicy policy, SecureRandom random, Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.policy = policy;
        this.random = random;
        this.clock = clock;
    }

    public OperatorResult execute(OperatorCommand command) {
        return switch (command) {
            case OperatorCommand.SetQuota quota -> store.setQuota(quota, clock.instant());
            case OperatorCommand.DisableUser disable -> store.disable(disable, clock.instant());
            case OperatorCommand.ResetPassword reset -> reset(reset);
        };
    }

    private OperatorResult reset(OperatorCommand.ResetPassword command) {
        byte[] bytes = new byte[24];
        SecretValue password;
        try {
            do {
                random.nextBytes(bytes);
                password =
                        SecretValue.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            } while (policy.violation(password.reveal(), command.email()).isPresent());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        var committed = store.reset(command, SecretValue.of(hasher.hash(password.reveal())), clock.instant());
        return new OperatorResult(committed.resourceId(), committed.sessionsRevoked(), password);
    }
}
