package dev.kairos.maintenance.commands.application;

import dev.kairos.security.SecretValue;
import java.time.Instant;

/** Each operation commits its mutation, revocations and audit entry in one control transaction. */
public interface OperatorStore {
    OperatorResult setQuota(OperatorCommand.SetQuota command, Instant at);

    OperatorResult disable(OperatorCommand.DisableUser command, Instant at);

    OperatorResult reset(OperatorCommand.ResetPassword command, SecretValue passwordHash, Instant at);
}
