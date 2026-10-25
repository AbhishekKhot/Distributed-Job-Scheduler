package dev.kairos.state.settle.application;

import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.RunId;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.ProtoTimestamps;
import dev.kairos.proto.v1.CallOutcome;
import dev.kairos.proto.v1.Result;
import java.nio.charset.StandardCharsets;

/** Rejects semantically invalid protobufs before database access; diagnostics never contain payload data. */
public final class ResultMetadata {
    private ResultMetadata() {}

    public static void requireValid(Result result) {
        var id = new RunId(ProtoIds.toUuid(result.getRunId()));
        if (result.getShard() != KairosId.shardOf(id.value()).value()
                || result.getFence() < 1
                || result.getAttempt() < 1
                || result.getAttempt() > Short.MAX_VALUE
                || result.getInfraAttempt() < 0
                || result.getInfraAttempt() > 3
                || result.getOutcomeValue() < 1
                || result.getOutcomeValue() > 6
                || result.getErrorClassValue() < 0
                || result.getErrorClassValue() > 14
                || result.getHttpStatus() != 0 && (result.getHttpStatus() < 100 || result.getHttpStatus() > 599)
                || result.getLatencyMs() < 0
                || result.getRetryAfterMs() < 0
                || result.getRetryAfterMs() > 604800000L
                || !result.hasStartedAt()
                || !result.hasFinishedAt()
                || result.getExecutorId().isBlank()
                || result.getExecutorId().length() > 128
                || result.getError().length() > 512
                || result.getResponseSnippet().getBytes(StandardCharsets.UTF_8).length > 1024) invalid();
        if (ProtoTimestamps.toInstant(result.getFinishedAt())
                .isBefore(ProtoTimestamps.toInstant(result.getStartedAt()))) invalid();
        boolean failure = result.getOutcome() == CallOutcome.CALL_OUTCOME_FAILED
                || result.getOutcome() == CallOutcome.CALL_OUTCOME_UNKNOWN;
        if (failure != (result.getErrorClassValue() != 0)) invalid();
        if (result.getOutcome() == CallOutcome.CALL_OUTCOME_DEFERRED && result.getSentMarker()) invalid();
        long outputs = result.getSealedOutputs().size();
        if (result.getOutputsCount() > 256) invalid();
        for (var entry : result.getOutputsMap().entrySet()) {
            if (!entry.getKey().matches("^[A-Za-z][A-Za-z0-9_-]{0,63}$")) invalid();
            outputs += entry.getKey().getBytes(StandardCharsets.UTF_8).length
                    + entry.getValue().getSerializedSize();
        }
        if (outputs > 24 * 1024) invalid();
    }

    private static void invalid() {
        throw new IllegalArgumentException("Invalid result metadata");
    }
}
