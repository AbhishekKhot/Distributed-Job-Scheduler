package dev.kairos.executor.gate.infrastructure;

import dev.kairos.engine.lock.SentMarker;
import dev.kairos.engine.lock.SentMarkerPort;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.executor.gate.application.ExecutionGate;
import dev.kairos.http.client.SendVetoedException;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Gate 4: durably mark the attempt in Redis immediately before the HTTP request bytes leave. */
@Component
@Order(4)
public final class SentMarkerGate implements ExecutionGate {

    private final SentMarkerPort markers;

    /** Creates the sent-marker gate. */
    public SentMarkerGate(SentMarkerPort markers) {
        this.markers = Objects.requireNonNull(markers, "markers");
    }

    @Override
    public String name() {
        return "sent_marker";
    }

    @Override
    public Phase phase() {
        return Phase.SEND;
    }

    @Override
    public GateResult apply(ExecutionContext context) throws SendVetoedException {
        SentMarker marker = new SentMarker(
                context.runId(),
                context.dispatch().getAttempt(),
                context.dispatch().getInfraAttempt());
        if (markers.mark(marker) == SentMarkerPort.MarkResult.MARKED) {
            return GateResult.CONTINUE;
        }
        throw new SendVetoedException("The sent marker is unavailable");
    }
}
