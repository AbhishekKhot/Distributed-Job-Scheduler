package dev.kairos.maintenance.commands.application;

import dev.kairos.security.SecretValue;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Committed result. The secret wrapper prevents accidental diagnostic disclosure. */
public record OperatorResult(
        UUID resourceId, int sessionsRevoked, @Nullable SecretValue temporaryPassword) {}
