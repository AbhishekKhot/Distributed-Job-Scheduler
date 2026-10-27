package dev.kairos.executor.gate.application;

import dev.kairos.executor.gate.application.ExecutionGate.GateResult;
import dev.kairos.executor.gate.application.ExecutionGate.Phase;
import dev.kairos.http.client.SendObserver;
import dev.kairos.http.client.SendVetoedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;

/** Runs the four gates in their required order and exposes the send-time gates as an HTTP observer. */
public final class ExecutionGateChain {

    public static final String METRIC = "kairos.execution.gate";

    private static final List<String> ORDER = List.of("run_lock", "fenced_claim", "send_guard", "sent_marker");

    private final List<ExecutionGate> gates;
    private final MeterRegistry meters;

    /** Creates a chain in lock, claim, send-guard, sent-marker order. */
    public ExecutionGateChain(List<ExecutionGate> gates, MeterRegistry meters) {
        this.gates = List.copyOf(gates);
        this.meters = Objects.requireNonNull(meters, "meters");
        if (this.gates.size() != ORDER.size()) {
            throw new IllegalArgumentException("Exactly four execution gates are required");
        }
        for (int i = 0; i < ORDER.size(); i++) {
            ExecutionGate gate = this.gates.get(i);
            Phase expected = i < 2 ? Phase.CLAIM : Phase.SEND;
            if (!ORDER.get(i).equals(gate.name()) || expected != gate.phase()) {
                throw new IllegalArgumentException("Execution gates must be ordered as " + ORDER);
            }
        }
    }

    /** Runs lock acquisition and fenced claim before invoking the execution action. */
    public boolean claim(ExecutionContext context) throws Exception {
        return run(Phase.CLAIM, context);
    }

    /** Returns an observer that performs the final lease check and sent-marker write. */
    public SendObserver sendObserver(ExecutionContext context) {
        return _ -> {
            try {
                if (!run(Phase.SEND, context)) {
                    throw new SendVetoedException("The execution send gates rejected the request");
                }
            } catch (SendVetoedException veto) {
                throw veto;
            } catch (Exception failure) {
                throw new SendVetoedException("The execution send gates are unavailable", failure);
            }
        };
    }

    private boolean run(Phase phase, ExecutionContext context) throws Exception {
        for (ExecutionGate gate : gates) {
            if (gate.phase() != phase) {
                continue;
            }
            GateResult decision;
            try {
                decision = gate.apply(context);
            } catch (Exception failure) {
                record(
                        gate.name(),
                        failure instanceof RunLockHeldException
                                ? "held"
                                : failure instanceof SendVetoedException ? "vetoed" : "unavailable");
                throw failure;
            }
            record(gate.name(), decision == GateResult.CONTINUE ? "passed" : "rejected");
            if (decision == GateResult.DROP) {
                return false;
            }
        }
        return true;
    }

    private void record(String gate, String outcome) {
        meters.counter(METRIC, "gate", gate, "outcome", outcome).increment();
    }
}
